package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.event.DishPublishedEvent;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 菜品发布：草稿 → 进找菜库。
 *
 * <p>它是"AI 生成"和"能被别人搜到"之间那道门。没有它，AI 生成的菜永远只躺在
 * 「我的菜品」里，召回接口一条也搜不到 —— 整个 AI 功能其实是没闭环的。
 *
 * <p><b>为什么需要一道校验闸门，而不是允许直接发布：</b>
 * 这道门后面是推荐池。低质内容一旦进去，会被推荐算法反复推给更多用户，
 * 而且 AI 是能批量生产的 —— 放行等于让污染自动放大。
 * 校验规则都设得很低（3 条步骤、2 种主料），目的不是"选优"，
 * 而是拦住明显不完整的残次品。
 *
 * <p><b>两条发布路径共用同一套校验：</b>
 * <ul>
 *   <li>自动：生成成功后立刻试一次（用户不用多点一下）</li>
 *   <li>手动：用户点「发布到找菜库」，用于自动发布失败后（比如当时重名）再补发</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DishPublishService {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;

    /** 步骤条数下限。低于这个数基本是模型没写完 */
    private static final int MIN_STEPS = 3;

    /**
     * 食材种数下限。
     *
     * <p><b>数的是全部食材，不是只看主料。</b>这一点踩过坑：
     * 落库时只把 AI 输出的**第一个**食材标成主料（见 {@code DishDraftWriter}），
     * 按"主料 ≥ 2"设门槛会把「土豆 + 胡萝卜」这种再正常不过的菜也拦在门外，
     * 而且提示写的是"主料不够"，看的人完全想不到问题出在标记规则上。
     *
     * <p><b>判据的来历（2026-09 改过）：</b>原来数的是「有主数据 ID 的食材」，
     * 因为那会儿索引里只有 {@code ingredientIds}，表外食材（用户自由输入的，如「松露」）
     * 没有 ID 就进不了索引 —— 放行它们会造出「发布成功但一条都搜不到」的菜。
     * 现在索引里多了 {@code ingredientNames}，表外食材靠名字同样能被召回
     * （见 {@code EsDslBuilder} 的双路查询），「能不能搜到」对所有食材都成立了。
     * <p>于是这道闸门回归它本来的意义：<b>拦住食材太单薄的残次品</b>，
     * 而不是替检索机制的缺陷背锅。
     */
    private static final int MIN_INGREDIENTS = 2;

    private static final int MAX_NAME_LENGTH = 20;

    private final DishMapper dishMapper;

    private final DishIngredientMapper dishIngredientMapper;

    /**
     * 发布事件靠它发出去，具体动作（同步 ES）由
     * {@code DishEsSyncListener} 在**事务提交之后**执行。
     * 这样即使 ES 挂了也不影响发布结果，二者不会互相拖累。
     */
    private final ApplicationEventPublisher events;

    // ==================================================================
    // 校验
    // ==================================================================

    /**
     * 发布校验的结论。
     *
     * @param problems 具体哪几条不满足。前端要把它直接显示给用户，
     *                 所以每条都必须是"用户看得懂、知道该做什么"的话，
     *                 而不是 {@code "invalid dish"} 这种。
     */
    public record CheckResult(boolean pass, List<String> problems) {

        public String reasonText() {
            return problems.isEmpty() ? "" : String.join("；", problems);
        }
    }

    /**
     * 跑一遍发布校验。
     *
     * <p>不修改任何数据，所以详情页可以随时调它来显示「还差什么」。
     */
    public CheckResult check(Dish dish) {
        List<String> problems = new ArrayList<>();

        // ---------- 菜名 ----------
        String name = dish.getName();
        if (name == null || name.isBlank()) {
            problems.add("还没有菜名");
        } else if (name.length() > MAX_NAME_LENGTH) {
            problems.add("菜名超过 " + MAX_NAME_LENGTH + " 个字");
        }

        // ---------- 步骤 ----------
        List<DishStep> steps = dish.getStepJson();
        long validSteps = 0;
        if (steps != null) {
            validSteps = steps.stream()
                    .filter(s -> s != null && s.getText() != null && !s.getText().isBlank())
                    .count();
        }
        if (validSteps < MIN_STEPS) {
            problems.add("做法只有 " + validSteps + " 条，至少要 " + MIN_STEPS + " 条");
        }

        // ---------- 食材 ----------
        // 全部食材都算：有主数据 ID 的靠 ID 被搜到，表外食材靠名字被搜到 ——
        // 两条路在索引里都有对应字段（见 DishEsSyncService），所以这里不再区分
        long ingredientCount = ingredientsOf(dish.getId()).size();
        if (ingredientCount < MIN_INGREDIENTS) {
            problems.add("食材只有 " + ingredientCount + " 种，至少要 " + MIN_INGREDIENTS + " 种");
        }

        // ---------- 营养 ----------
        if (dish.getEnergyKcal() == null || dish.getEnergyKcal().signum() <= 0) {
            problems.add("缺少热量数据");
        }

        // ---------- 重名 ----------
        // 只和**已发布**的比：草稿各自私有，重名无所谓。
        // 不拦的话找菜库里会出现一堆名字一样的菜，用户没法区分该点哪个。
        if (dish.getNameKey() != null && !dish.getNameKey().isBlank()) {
            Long duplicated = dishMapper.selectCount(Wrappers.<Dish>lambdaQuery()
                    .eq(Dish::getNameKey, dish.getNameKey())
                    .eq(Dish::getStatus, STATUS_PUBLISHED)
                    .ne(Dish::getId, dish.getId()));
            if (duplicated != null && duplicated > 0) {
                problems.add("找菜库里已经有同名（或名字很接近）的菜了");
            }
        }

        return new CheckResult(problems.isEmpty(), problems);
    }

    /** 详情页用：查一遍校验结果，给用户提示"还差什么" */
    public CheckResult checkForOwner(long userId, long dishId) {
        return check(requireOwnDish(userId, dishId));
    }

    // ==================================================================
    // 发布 / 撤回
    // ==================================================================

    /**
     * 手动发布。
     *
     * <p>{@code @Transactional} 不只是为了原子性 ——
     * 它是发布事件能被 AFTER_COMMIT 监听器收到的<b>前提</b>：
     * 没有事务时 {@code @TransactionalEventListener} 会静默不执行，
     * 表现是"接口返回成功，但 ES 里什么都没有"，而且不报任何错。
     */
    @Transactional
    public String publish(long userId, long dishId) {
        Dish dish = requireOwnDish(userId, dishId);

        // 幂等：重复点不该报错，前端连点两下是常事
        if (Objects.equals(dish.getStatus(), STATUS_PUBLISHED)) {
            return "这道菜已经在找菜库里了";
        }
        if (!Objects.equals(dish.getStatus(), STATUS_DRAFT)) {
            // status=2（审核拒绝）/ 3（已下架）当前不支持用户自助发布
            throw new BusinessException("这道菜当前的状态不支持发布");
        }

        CheckResult result = check(dish);
        if (!result.pass()) {
            throw new BusinessException("还差一点，暂时发布不了：" + result.reasonText());
        }

        doPublish(dish);
        log.info("用户 {} 发布菜品 {}（{}）", userId, dishId, dish.getName());
        return "已发布到找菜库，其他人现在能搜到它了";
    }

    /**
     * 从找菜库撤回（回到草稿）。
     *
     * <p>菜本身不删 —— 用户可能只是想改一改再发，或者不想让别人看到。
     * 撤回后会发同一个事件，监听方发现状态不是"已发布"就把索引里那份删掉。
     */
    @Transactional
    public String unpublish(long userId, long dishId) {
        Dish dish = requireOwnDish(userId, dishId);

        if (!Objects.equals(dish.getStatus(), STATUS_PUBLISHED)) {
            return "这道菜本来就不在找菜库里";
        }

        boolean updated = dishMapper.update(null, Wrappers.<Dish>lambdaUpdate()
                .eq(Dish::getId, dishId)
                .eq(Dish::getStatus, STATUS_PUBLISHED)
                .set(Dish::getStatus, STATUS_DRAFT)
                .set(Dish::getPublishTime, null)) > 0;

        if (!updated) {
            throw new BusinessException("状态已经变了，请刷新后重试");
        }

        events.publishEvent(new DishPublishedEvent(dishId));
        log.info("用户 {} 撤回菜品 {}", userId, dishId);
        return "已从找菜库撤下，仍保留在你的菜品里";
    }

    /**
     * 生成成功之后尝试自动发布。
     *
     * <p>为什么自动：手动发布会让用户多一步操作，而且大部分人不会去点 ——
     * 结果就是"AI 生成的菜永远搜不到"，功能看起来是坏的。
     *
     * <p>为什么失败只记日志不抛异常：这一步是锦上添花。
     * 它跑在「生成成功」之后，如果因为"刚好有人发了一道重名菜"就抛异常，
     * 会把整个生成事务回滚掉 —— 用户等了 20 秒，最后连草稿都没有。
     * 校验没过就留在草稿态，用户想发的时候可以在详情页点手动发布。
     */
    @Transactional
    public void tryAutoPublish(long dishId) {
        try {
            Dish dish = dishMapper.selectById(dishId);
            if (dish == null || !Objects.equals(dish.getStatus(), STATUS_DRAFT)) {
                return;
            }

            CheckResult result = check(dish);
            if (!result.pass()) {
                log.info("菜品 {} 未通过自动发布校验，留在草稿：{}", dishId, result.problems());
                return;
            }

            doPublish(dish);
            log.info("菜品 {} 已自动发布到找菜库", dishId);
        } catch (Exception e) {
            log.warn("菜品 {} 自动发布失败，留在草稿：{}", dishId, e.getMessage());
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 真正把状态改成已发布并发事件。
     *
     * <p>用「带状态条件的 update」而不是"先查再改"：并发下两个请求都查到草稿、
     * 都执行 update，就会重复发两次事件。加上 {@code status = 0} 这个条件之后，
     * 只有第一个请求能改成功（影响 1 行），第二个影响 0 行被识别出来。
     */
    private void doPublish(Dish dish) {
        boolean updated = dishMapper.update(null, Wrappers.<Dish>lambdaUpdate()
                .eq(Dish::getId, dish.getId())
                .eq(Dish::getStatus, STATUS_DRAFT)
                .set(Dish::getStatus, STATUS_PUBLISHED)
                .set(Dish::getPublishTime, LocalDateTime.now())) > 0;

        if (!updated) {
            throw new BusinessException("状态已经变了，请刷新后重试");
        }

        events.publishEvent(new DishPublishedEvent(dish.getId()));
    }

    private List<DishIngredient> ingredientsOf(Long dishId) {
        return dishIngredientMapper.selectList(
                Wrappers.<DishIngredient>lambdaQuery().eq(DishIngredient::getDishId, dishId));
    }

    /**
     * 校验归属。
     *
     * <p>和任务、收藏保持同一套话术：不区分"不存在"和"不是你的"，
     * 否则这个接口就成了"探测别人有哪些菜"的工具。
     */
    private Dish requireOwnDish(long userId, long dishId) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null || !Objects.equals(dish.getCreatorId(), userId)) {
            throw new BusinessException("菜品不存在");
        }
        return dish;
    }
}
