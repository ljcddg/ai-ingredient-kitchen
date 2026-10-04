package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.ai.AiTaskStatus;
import com.apesource.dishservice.ai.CancelOutcome;
import com.apesource.dishservice.ai.DegradedModeGuard;
import com.apesource.dishservice.ai.DishTaskQueue;
import com.apesource.dishservice.ai.EnqueueResult;
import com.apesource.dishservice.dto.DishDetailView;
import com.apesource.dishservice.dto.DishGeneratePayload;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.DishGenerateRequest;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.DishTaskView;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.AiGenerateTask;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.AiGenerateTaskMapper;
import com.apesource.dishservice.mapper.DishCommentMapper;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 菜品生成的门面 —— 前端所有和"AI 做菜"有关的操作都从这里进。
 *
 * <p>它自己不执行生成（那是队列 + {@code DishGenerationExecutor} 的事），
 * 只负责：参数校验、食材归一、落任务、入队、以及把状态翻译成前端能看的样子。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishGenerateService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MY_TASK_LIMIT = 20;
    private static final int COMMENT_LIMIT = 20;

    private final DishTaskQueue queue;
    private final IngredientResolver ingredientResolver;
    private final AiGenerateTaskMapper taskMapper;
    private final DishMapper dishMapper;
    private final DishIngredientMapper dishIngredientMapper;
    private final DishCommentMapper dishCommentMapper;
    private final ObjectMapper objectMapper;

    /**
     * 只为详情接口补两个"当前用户个人状态"字段（是否已收藏 / 我打的分）而引入。
     *
     * <p>反过来的依赖不成立（{@code DishInteractionService} 不认识生成链路），
     * 所以这里不存在循环依赖。
     */
    private final DishInteractionService interactionService;

    /** 约束自检要按名字/别名比对（"点选牛肉、忌口写牛里脊"也要能对上） */
    private final IngredientDirectory directory;

    /** 检索故障时的生成限流（说明文档第四节） */
    private final DegradedModeGuard degradedModeGuard;

    // ================================================================== 提交

    /**
     * 提交一次生成请求。
     *
     * <p>这个方法**立刻返回**，不等 AI 出结果 —— 生成要 10 秒上下，
     * 让 HTTP 请求挂在那儿等是不可接受的。前端拿到 taskId 后自己轮询。
     */
    public DishTaskView submit(long userId, DishGenerateRequest request) {
        List<IngredientRef> ingredients = ingredientResolver.resolve(
                request.getIngredientIds(), request.getFreeIngredients());
        if (ingredients.isEmpty()) {
            throw new BusinessException("请至少选择或输入一样食材");
        }
        int servings = request.getServings() == null ? 2 : Math.max(1, Math.min(8, request.getServings()));
        List<String> tastes = request.getTastes() == null ? List.of() : request.getTastes();

        GenerateConstraints constraints = request.toConstraints();
        // 自相矛盾的条件在调模型之前就拒掉：否则必然失败，还要白跑满重试预算
        // （默认 2 次，实测单次生成几十秒到几分钟，用户就是干等）
        rejectContradictoryConstraints(ingredients, constraints);

        DishGeneratePayload payload = new DishGeneratePayload(ingredients, tastes, servings, constraints);
        // 约束必须进签名：否则"忌口牛肉"和"没有忌口"两次提交会被单飞判成同一个请求，
        // 第二次直接复用第一次的任务 —— 用户以为忌口生效了，其实拿到的是别人的结果
        String signature = signatureOf(ingredients, tastes, servings, payload.constraintsOrNone());

        // 单飞：同一个用户、同样的输入，短时间内重复提交直接复用已有任务
        AiGenerateTask existing = findActiveBySignature(userId, signature);
        if (existing != null) {
            log.info("命中单飞：复用任务 {}（signature={}）", existing.getId(), signature);
            return toView(existing, null);
        }

        // 降级配额：检索引擎不可用时限制生成频率（说明文档第四节）。
        // 放在单飞之后：命中单飞并没有新建任务，不该算作用了一次配额。
        degradedModeGuard.checkGenerationQuota(userId);

        AiGenerateTask task = new AiGenerateTask();
        task.setUserId(userId);
        task.setRequestSignature(signature);
        task.setRequestJson(writeJson(payload));
        task.setStatus(AiTaskStatus.PENDING.getCode());
        task.setCostMs(0);
        task.setQueueWaitMs(0);
        taskMapper.insert(task);

        EnqueueResult enqueued = queue.submit(task.getId(), userId);
        if (!enqueued.accepted()) {
            // 队列满了：把刚落的这条任务标成失败，不能让它在库里悬着
            updateStatus(task.getId(), AiTaskStatus.FAILED, enqueued.reason());
            throw new BusinessException(enqueued.reason());
        }

        log.info("用户 {} 提交生成任务 {}，队列位置 {}/{}", userId, task.getId(),
                enqueued.position(), enqueued.queueSize());
        return toView(task, enqueued.position());
    }

    /**
     * 约束自检：把「无论模型怎么生成都不可能满足」的组合在提交阶段就拒掉。
     *
     * <p><b>为什么必须在这里拦，而不是等校验器去发现：</b>
     * 这类请求在生成阶段的结果是<b>必然失败</b>，而且会老老实实跑满重试预算
     * （默认 2 次；实测单次生成几十秒到几分钟）。用户干等几分钟，
     * 最后拿到的还是一句"生成失败"——而真正的原因是条件本身矛盾，
     * 说清楚他改一下就能立刻重试。
     *
     * <p>两类冲突：
     * <ol>
     *   <li><b>必用 ∩ 禁用 ≠ ∅</b>：同一个食材既要求用上、又不许出现；</li>
     *   <li><b>手边食材 ∩ 禁用 ≠ ∅</b>：校验器要求"用户提供的每样食材都要用上"，
     *       所以只要其中一样被忌口，这个请求同样无解。</li>
     * </ol>
     * 比对同时看 ID 与名字（含别名），否则"点选牛肉、忌口写「牛里脊」"会漏过去。
     */
    private void rejectContradictoryConstraints(List<IngredientRef> ingredients, GenerateConstraints c) {
        if (c == null || c.noConstraint()) {
            return;
        }
        Set<Long> excludeIds = c.excludeIdSet();
        Set<String> excludeKeys = new LinkedHashSet<>();
        for (String name : c.excludeNames()) {
            excludeKeys.add(IngredientDirectory.key(name));
        }

        for (IngredientRef ref : ingredients) {
            if (ref == null) {
                continue;
            }
            if (isExcluded(ref.id(), ref.name(), excludeIds, excludeKeys)) {
                throw new BusinessException("「" + ref.name() + "」在你的忌口清单里，"
                        + "而生成结果必须用上你提供的每样食材，请把它从食材中去掉再试");
            }
        }

        for (Long id : c.mustUseIdSet()) {
            String name = directory.nameOf(id);
            if (isExcluded(id, name, excludeIds, excludeKeys)) {
                throw new BusinessException("「" + (name != null ? name : ("食材#" + id))
                        + "」既要求必用、又在忌口清单里，两者只能留一个");
            }
        }
        for (String name : c.mustUseNames()) {
            if (isExcluded(directory.resolveId(name), name, excludeIds, excludeKeys)) {
                throw new BusinessException("「" + name + "」既要求必用、又在忌口清单里，两者只能留一个");
            }
        }
    }

    /** 这个食材（按 ID 与名字两条路）是否落在忌口清单里 */
    private boolean isExcluded(Long id, String name, Set<Long> excludeIds, Set<String> excludeKeys) {
        if (id != null && id > 0 && excludeIds.contains(id)) {
            return true;
        }
        String key = IngredientDirectory.key(name);
        if (key == null) {
            return false;
        }
        if (excludeKeys.contains(key)) {
            return true;
        }
        Long resolved = directory.resolveId(name);
        return resolved != null && resolved > 0 && excludeIds.contains(resolved);
    }

    // ================================================================== 我的任务
    /** 我的任务列表（最近的在前），带实时排队位置 */
    public List<DishTaskView> myTasks(long userId) {
        List<AiGenerateTask> tasks = taskMapper.selectList(
                Wrappers.<AiGenerateTask>lambdaQuery()
                        .eq(AiGenerateTask::getUserId, userId)
                        .orderByDesc(AiGenerateTask::getCreateTime)
                        .last("LIMIT " + MY_TASK_LIMIT));
        if (tasks.isEmpty()) {
            return List.of();
        }

        // 菜名一次性查出来，避免每条任务都去查一次菜
        List<Long> dishIds = tasks.stream()
                .map(AiGenerateTask::getResultDishId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> dishNames = new LinkedHashMap<>();
        if (!dishIds.isEmpty()) {
            for (Dish d : dishMapper.selectBatchIds(dishIds)) {
                dishNames.put(d.getId(), d.getName());
            }
        }

        List<DishTaskView> views = new ArrayList<>(tasks.size());
        for (AiGenerateTask t : tasks) {
            views.add(toView(t, null, dishNames.get(t.getResultDishId())));
        }
        return views;
    }

    // ================================================================== 取消 / 调序

    /**
     * 取消任务。只能取消自己的（队列侧会校验，这里先做一次快速拦截）。
     */
    public String cancel(long userId, long taskId) {
        AiGenerateTask task = requireOwnTask(userId, taskId);
        AiTaskStatus status = AiTaskStatus.of(task.getStatus());
        if (status.isTerminal()) {
            // 已经结束的任务，取消当幂等处理，不报错 —— 前端连点两下不该看到红字
            return "任务已" + status.getText();
        }

        CancelOutcome outcome = queue.cancel(taskId, userId);
        if (outcome == CancelOutcome.NOT_OWNER) {
            throw new BusinessException("任务不存在");
        }
        // 条件更新：只在还是"排队中/生成中"时才改，避免把刚跑完的成功状态覆盖掉
        boolean updated = taskMapper.update(null,
                Wrappers.<AiGenerateTask>lambdaUpdate()
                        .eq(AiGenerateTask::getId, taskId)
                        .in(AiGenerateTask::getStatus,
                                AiTaskStatus.PENDING.getCode(), AiTaskStatus.RUNNING.getCode())
                        .set(AiGenerateTask::getStatus, AiTaskStatus.CANCELLED.getCode())) > 0;

        if (!updated) {
            // 竞争失败：影响 0 行说明状态已经不是"排队中/生成中"。
            // 最常见的情况是"生成恰好在这一瞬间完成" —— 此时如实告诉用户，
            // 而不是回一句"已取消"让他以为取消生效了，结果发现菜还是出来了。
            AiGenerateTask latest = taskMapper.selectById(taskId);
            String latestText = latest == null
                    ? "结束"
                    : AiTaskStatus.of(latest.getStatus()).getText();
            log.info("用户 {} 取消任务 {} 未生效：状态已变为 {}", userId, taskId, latestText);
            return "任务已" + latestText + "，取消没有生效";
        }

        log.info("用户 {} 取消任务 {}：队列结果={}", userId, taskId, outcome);
        return outcome == CancelOutcome.CANCELLED_WAITING ? "已取消（尚未开始）" : "已取消";
    }

    /**
     * 把一条**已结束**的任务记录从「我的任务」里移除。
     *
     * <p><b>它和 {@link #cancel} 是两件事，不能合并成一个接口：</b>
     * <ul>
     *   <li><b>取消</b>针对**进行中**的任务 —— 排队中的真从队列摘掉（省资源），
     *       生成中的只能逻辑取消（AI 调用已经发出去了，拦不住）</li>
     *   <li><b>移除</b>针对**已结束**的任务 —— 只是把记录从列表里清掉，
     *       它本来就已经不在队列里了</li>
     * </ul>
     *
     * <p>不允许移除进行中的任务，是因为那样会造出一个"幽灵任务"：
     * 用户以为任务没了，后台的 AI 调用却还在跑，跑完还会往库里落一道菜。
     * 想清掉就得先取消 —— 保留一条明确的状态迁移路径，
     * 比允许任意删除之后再去解释残留数据要省事得多。
     *
     * <p><b>只删任务记录，不碰菜品。</b>生成成功的那道菜是用户的产出，
     * 清掉任务不该连带清掉它 —— 在「我的菜品」里仍然找得到。
     */
    public String deleteTask(long userId, long taskId) {
        AiGenerateTask task = requireOwnTask(userId, taskId);
        AiTaskStatus status = AiTaskStatus.of(task.getStatus());

        if (!status.isTerminal()) {
            throw new BusinessException("任务还在" + status.getText() + "，请先取消再移除");
        }

        // 实体上有 @TableLogic，deleteById 实际是 update deleted = 1。
        // 保留历史记录，排查"用户说做过一道菜但列表里没有"这类问题时还用得上。
        taskMapper.deleteById(taskId);
        log.info("用户 {} 移除任务记录 {}（原状态：{}）", userId, taskId, status.getText());
        return "已移除";
    }

    /**
     * 一次清掉「我的任务」里所有**已结束**的记录。
     *
     * <p>为什么要批量：任务列表最多留 20 条（见 {@code MY_TASK_LIMIT}），
     * 做几道菜之后就全是历史，把右栏的结果区一路挤到屏幕下面去。
     * 用户想看的是"现在在做什么"，历史该收起来 —— 而单条移除要点二十次。
     *
     * <p><b>只清终态，进行中的任务碰都不碰。</b>删掉正在跑的任务会造出
     * "幽灵任务"（用户以为没了，后台的 AI 调用还在跑、跑完照样落一道菜），
     * 理由与 {@link #deleteTask} 完全一致。
     *
     * <p><b>只删任务记录，不碰菜品。</b>清历史不该让用户的菜跟着消失 ——
     * 生成成功的那道菜在「我的菜品」里仍然找得到。
     *
     * @return 实际清掉的条数
     */
    public int clearFinishedTasks(long userId) {
        List<Integer> terminalCodes = new ArrayList<>();
        for (AiTaskStatus s : AiTaskStatus.values()) {
            if (s.isTerminal()) {
                terminalCodes.add(s.getCode());
            }
        }

        // 实体上有 @TableLogic，delete 实际是 update deleted = 1，返回值就是影响行数
        int removed = taskMapper.delete(
                Wrappers.<AiGenerateTask>lambdaQuery()
                        .eq(AiGenerateTask::getUserId, userId)
                        .in(AiGenerateTask::getStatus, terminalCodes));

        log.info("用户 {} 清空已结束的任务记录，共 {} 条", userId, removed);
        return removed;
    }

    /**
     * 调整排队顺序 —— 只能在自己提交的任务之间调。
     */
    public boolean move(long userId, long taskId, int orderAmongMine) {
        requireOwnTask(userId, taskId);
        boolean ok = queue.move(taskId, userId, orderAmongMine);
        if (!ok) {
            throw new BusinessException("该任务不在排队中，无法调整顺序");
        }
        return true;
    }

    // ================================================================== 详情

    /**
     * 菜品详情。
     *
     * <p>权限：草稿（status=0）只有创建者本人能看 —— 这是"AI 生成的先落草稿"这条规则
     * 在查询侧的落点，漏掉就会把别人还没发布的菜暴露出去。
     */
    public DishDetailView detail(long dishId, long userId) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        if (Objects.equals(dish.getStatus(), 0) && !Objects.equals(dish.getCreatorId(), userId)) {
            throw new BusinessException("菜品不存在");
        }

        DishDetailView view = new DishDetailView();
        view.setId(dish.getId());
        view.setName(dish.getName());
        view.setSummary(dish.getSummary());
        view.setCoverUrl(dish.getCoverUrl());
        view.setStatus(dish.getStatus());
        view.setSource(dish.getSource());
        view.setCreatorId(dish.getCreatorId());
        // 草稿的 publishTime 是 null，前端据此区分「还没发布」和「已发布」
        view.setPublishTime(dish.getPublishTime() == null
                ? null : dish.getPublishTime().format(TIME_FMT));
        view.setBaseServing(dish.getBaseServing());
        view.setDifficulty(dish.getDifficulty());
        view.setPrepTimeMin(dish.getPrepTimeMin());
        view.setCookTimeMin(dish.getCookTimeMin());
        view.setSpicyLevel(dish.getSpicyLevel());
        if (dish.getTasteTag() != null && !dish.getTasteTag().isBlank()) {
            view.setTastes(List.of(dish.getTasteTag().split(",")));
        }
        view.setSteps(dish.getStepJson() == null ? List.of() : dish.getStepJson());
        view.setSeasonings(dish.getSeasonings() == null ? List.of() : dish.getSeasonings());
        view.setEquipment(dish.getEquipment() == null ? List.of() : dish.getEquipment());
        view.setChefTips(dish.getChefTips() == null ? List.of() : dish.getChefTips());
        view.setSubstitutes(dish.getSubstitutes() == null ? List.of() : dish.getSubstitutes());

        // 食材清单
        List<DishIngredient> rows = dishIngredientMapper.selectList(
                Wrappers.<DishIngredient>lambdaQuery()
                        .eq(DishIngredient::getDishId, dishId)
                        .orderByAsc(DishIngredient::getSortNo));
        List<DishDetailView.IngredientItem> items = new ArrayList<>(rows.size());
        for (DishIngredient row : rows) {
            DishDetailView.IngredientItem item = new DishDetailView.IngredientItem();
            item.setIngredientId(row.getIngredientId());
            item.setName(row.getIngredientName());
            item.setAmountGram(row.getAmountGram() == null ? null : row.getAmountGram().intValue());
            item.setMain(Objects.equals(row.getMainFlag(), 1));
            items.add(item);
        }
        view.setIngredients(items);

        // 营养 —— 带上来源说明，前端必须原样展示，不能只给数字
        if (dish.getNutritionSource() != null && dish.getNutritionSource() > 0) {
            DishDetailView.NutritionView n = new DishDetailView.NutritionView();
            n.setCalories(toInt(dish.getEnergyKcal()));
            n.setProteinGram(toInt(dish.getProteinG()));
            n.setFatGram(toInt(dish.getFatG()));
            n.setCarbGram(toInt(dish.getCarbG()));
            n.setFiberGram(toInt(dish.getFiberG()));
            n.setSodiumMg(toInt(dish.getSodiumMg()));
            n.setSource(dish.getNutritionSource() == 1 ? "AI 估算，仅供参考" : "按食材精确计算");
            // 代码和文案都给：前端按代码取映射表，保证列表页/详情页口径一致
            n.setNutritionSource(dish.getNutritionSource());
            n.setVerified(Objects.equals(dish.getNutritionVerified(), 1));
            view.setNutrition(n);
        }

        // 评分
        DishDetailView.RatingView rating = new DishDetailView.RatingView();
        rating.setAverage(dish.getRatingAvg() == null ? 0d : dish.getRatingAvg().doubleValue());
        rating.setCount(dish.getRatingCount() == null ? 0 : dish.getRatingCount());
        rating.setCollectCount(dish.getCollectCount() == null ? 0 : dish.getCollectCount());
        view.setRating(rating);

        // 评论（草稿阶段一般还没有，但接口保持一致）
        view.setComments(dishCommentMapper.selectViewsByDish(dishId, COMMENT_LIMIT));

        // 当前用户的个人状态 —— 详情页靠它决定收藏按钮亮不亮、评分控件显示几颗星。
        // 特意放在最后：前面任何一步抛异常（比如无权查看别人的草稿）都不该白跑这两次查询。
        view.setCollected(interactionService.isCollected(userId, dishId));
        view.setMyScore(interactionService.myScore(userId, dishId));
        return view;
    }

    // ================================================================== 内部

    private AiGenerateTask requireOwnTask(long userId, long taskId) {
        AiGenerateTask task = taskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)) {
            // 不区分"不存在"和"不是你的"，避免被拿来探测别人有哪些任务
            throw new BusinessException("任务不存在");
        }
        return task;
    }

    private AiGenerateTask findActiveBySignature(long userId, String signature) {
        return taskMapper.selectOne(
                Wrappers.<AiGenerateTask>lambdaQuery()
                        .eq(AiGenerateTask::getUserId, userId)
                        .eq(AiGenerateTask::getRequestSignature, signature)
                        .in(AiGenerateTask::getStatus,
                                AiTaskStatus.PENDING.getCode(), AiTaskStatus.RUNNING.getCode())
                        .orderByDesc(AiGenerateTask::getCreateTime)
                        .last("LIMIT 1"));
    }

    private void updateStatus(long taskId, AiTaskStatus status, String errorMsg) {
        AiGenerateTask update = new AiGenerateTask();
        update.setId(taskId);
        update.setStatus(status.getCode());
        update.setErrorMsg(errorMsg);
        taskMapper.updateById(update);
    }

    private DishTaskView toView(AiGenerateTask task, Integer knownPosition) {
        return toView(task, knownPosition, null);
    }

    private DishTaskView toView(AiGenerateTask task, Integer knownPosition, String dishName) {
        AiTaskStatus status = AiTaskStatus.of(task.getStatus());
        DishTaskView view = new DishTaskView();
        view.setTaskId(task.getId());
        view.setStatus(status.getCode());
        view.setStatusText(status.getText());
        view.setPosition(knownPosition != null ? knownPosition : queue.position(task.getId()));
        view.setDishId(task.getResultDishId());
        view.setDishName(dishName);
        view.setErrorMsg(task.getErrorMsg());
        view.setCostMs(task.getCostMs());
        view.setQueueWaitMs(task.getQueueWaitMs());
        view.setSummary(summaryOf(task));
        view.setCreateTime(task.getCreateTime() == null ? null : task.getCreateTime().format(TIME_FMT));
        view.setCancellable(status.isCancellable());
        return view;
    }

    /**
     * 从请求快照里拼一句食材摘要。
     *
     * <p>排队中和生成中的任务还没有菜名，任务列表上如果只显示「排队中」，
     * 用户根本分不清哪条是哪次请求。
     *
     * <p>解析失败返回 null 而不是抛异常 —— 前端有兜底文案，
     * 不该因为一句摘要把整个任务列表接口搞挂。
     */
    private String summaryOf(AiGenerateTask task) {
        String json = task.getRequestJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            DishGeneratePayload payload = objectMapper.readValue(json, DishGeneratePayload.class);
            List<IngredientRef> list = payload.ingredients();
            if (list == null || list.isEmpty()) {
                return null;
            }
            List<String> names = list.stream()
                    .map(IngredientRef::name)
                    .filter(Objects::nonNull)
                    .limit(4)
                    .toList();
            if (names.isEmpty()) {
                return null;
            }
            String joined = String.join("、", names);
            return list.size() > 4 ? joined + " 等 " + list.size() + " 样" : joined;
        } catch (Exception e) {
            log.warn("解析任务 {} 的请求快照失败：{}", task.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 请求签名：食材名排序 + 口味排序 + 人数 + 约束，取 MD5。
     *
     * <p>**食材要先排序**，否则用户换一下点选顺序就会算出不同的签名，
     * 单飞就失效了（而实际上那就是同一个请求）。
     */
    private String signatureOf(List<IngredientRef> ingredients, List<String> tastes, int servings,
                               GenerateConstraints constraints) {
        String joined = ingredients.stream()
                .map(IngredientRef::name)
                .filter(Objects::nonNull)
                .sorted()
                .reduce("", (a, b) -> a + "|" + b);
        String tasteKey = tastes.stream().sorted().reduce("", (a, b) -> a + "|" + b);
        return md5(joined + "#" + tasteKey + "#" + servings + "#" + constraintsKey(constraints));
    }

    /**
     * 约束的规范化串。
     *
     * <p>同样要排序：约束列表的顺序不影响语义，但会影响签名 ——
     * 排序后，「忌口牛肉、香菜」和「忌口香菜、牛肉」才算同一个请求。
     */
    private String constraintsKey(GenerateConstraints c) {
        if (c == null || c.noConstraint()) {
            return "";
        }
        return sortedIds(c.mustUseIdSet()) + "/" + sortedNames(c.mustUseNames())
                + "/" + sortedIds(c.excludeIdSet()) + "/" + sortedNames(c.excludeNames())
                + "/" + (c.maxCookTimeMin() == null ? "" : c.maxCookTimeMin())
                + "/" + sortedNames(c.equipmentNames());
    }

    private String sortedIds(java.util.Collection<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).reduce("", (a, b) -> a + "," + b);
    }

    private String sortedNames(java.util.Collection<String> names) {
        return names.stream().sorted().reduce("", (a, b) -> a + "," + b);
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算请求签名失败", e);
        }
    }

    private String writeJson(DishGeneratePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException("请求参数序列化失败");
        }
    }

    private Integer toInt(BigDecimal value) {
        return value == null ? null : value.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    /** 供调试用：队列当前快照 */
    public Map<String, Object> queueSnapshot() {
        return queue.snapshot();
    }
}
