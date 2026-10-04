package com.apesource.dishservice.ai;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.ManualDishRequest;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.DishPublishService;
import com.apesource.dishservice.service.IngredientCandidateService;
import com.apesource.dishservice.service.IngredientDirectory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 「手写菜谱」：用户自己写的菜，系统只负责算和存。
 *
 * <h3>为什么单独一个类，而不是塞进 {@link DishDraftWriter}</h3>
 * 两者的输入根本不是一个东西：那边是模型的 {@code DishDraft}（字段固定、必须校验），
 * 这边是用户随手填的表单（字段可缺省、要一边填一边试算）。
 * 硬合到一起会让"校验"长出两套分支，谁都不敢动。
 * 但<b>落库口径必须一样</b>：草稿态、来源标记、食材归一化、候选表登记，
 * 都照 {@link DishDraftWriter} 的做法来，不然同一个菜从两条路进来会长得不一样。
 *
 * <h3>为什么先存草稿再自动发布</h3>
 * 和 AI 生成完全对称（见 {@code DishGenerationExecutor} 调 {@code tryAutoPublish}）：
 * 存草稿是<b>一定成功</b>的（用户填了半天，不能因为"刚好有人发了同名菜"就整个丢掉），
 * 发布是<b>尽力而为</b>的（没过校验就留在草稿，把差什么告诉用户）。
 *
 * <h3>发布门槛提前拦</h3>
 * 「工序」上的错误（步骤只有 2 条、食材只有 1 样）在<b>提交前</b>就说清楚，
 * 别让用户上传完才发现存进去的是一道永远发不出去的菜 —— 现在没有编辑草稿的入口，
 * 发不出去等于白填。所以这里的规则与 {@code DishPublishService.check} 保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualDishWriter {

    /** 菜品来源：3-用户自定义（1-官方精选，2-AI 生成） */
    public static final int SOURCE_USER = 3;

    /** 与 DishPublishService 对齐的发布门槛 */
    private static final int MIN_INGREDIENTS = 2;
    private static final int MIN_STEPS = 3;
    private static final int MAX_NAME_LENGTH = 20;

    private final DishMapper dishMapper;
    private final DishIngredientMapper dishIngredientMapper;

    /** 按成分表算营养 —— 手写菜谱的营养值全部来自这里，不掺任何估算 */
    private final NutritionCalculator nutritionCalculator;

    /** 食材名称归一化（P1-1）：手输的名字也要挂上主数据 ID */
    private final IngredientDirectory ingredientDirectory;

    /** 主数据里还没有的食材记进候选表，够次数转正 */
    private final IngredientCandidateService ingredientCandidateService;

    /** 食材表里的一行（试算结果，带没对上主数据的标记） */
    public record PreviewItem(String name, Long ingredientId, String masterName, BigDecimal grams,
                              boolean matched) {
    }

    /**
     * 试算结果。
     *
     * <p>{@code problems} 是<b>会挡住提交</b>的硬问题；{@code warnings} 只是提醒
     * （比如有食材没对上成分表，算出来的热量偏低）。两者分开，前端才好用两种颜色显示，
     * 也不会出现"提示有警告但按钮还能点"这种自相矛盾的界面。
     */
    public record Preview(int servings, double calories, double proteinGram, double fatGram, double carbGram,
                          double fiberGram, double sodiumMg, double coverage, boolean trustable,
                          int resolvedItems, int totalItems, List<String> unresolved, String text,
                          List<PreviewItem> items, List<String> problems, List<String> warnings,
                          boolean ready) {
    }

    // ==================================================================
    // 试算（不写库）
    // ==================================================================

    /**
     * 试算：算出每人份营养、逐行标出有没有对上主数据、把硬问题一次说全。
     *
     * <p>用户每改一次克数都会调它，所以这里<b>绝不写库</b>，
     * 也不能抛异常（表单填一半是常态，抛出去前端只能显示一句"服务器开小差了"）。
     */
    public Preview preview(ManualDishRequest request) {
        return preview(null, request);
    }

    /**
     * 带身份试算：多一条"这个菜名你自己是不是已经用过了"。
     *
     * <p>为什么非得有 userId 才敢报这条：{@code t_dish} 上有唯一键
     * {@code uk_name_creator(name_key, creator_id)}（"同一创作者不可重复同名菜"）。
     * 前端面板不查库就放行的话，用户填完点上传会撞唯一键，异常落到兜底处理器里
     * 变成一句"服务器开小差了"——用户白填一遍还不知道为什么（实测就是这个症状）。
     * 提前查一次，按钮就能在<b>上传前</b>灰掉，话也说在明处。
     */
    public Preview preview(Long userId, ManualDishRequest request) {
        ManualDishRequest req = request == null
                ? new ManualDishRequest(null, null, null, null, null, null, null, null, null, null, null, null)
                : request;
        int servings = req.servingsOrDefault();

        List<NutritionCalculator.Item> forCalc = new ArrayList<>();
        List<PreviewItem> rows = new ArrayList<>();
        for (ManualDishRequest.Item raw : rowsOf(req)) {
            Long id = resolveId(raw);
            rows.add(new PreviewItem(raw.name(), id,
                    id == null ? null : ingredientDirectory.nameOf(id), raw.grams(), id != null));
            forCalc.add(new NutritionCalculator.Item(raw.name(), raw.grams()));
        }

        NutritionCalculator.Result r = nutritionCalculator.calculate(forCalc, servings);
        String name = nameOf(req);
        List<String> steps = validSteps(req);
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- 硬问题：与 DishPublishService.check 同一套门槛 ----
        if (name.isEmpty()) {
            problems.add("还没有填菜名");
        } else if (name.length() > MAX_NAME_LENGTH) {
            problems.add("菜名超过 " + MAX_NAME_LENGTH + " 个字（现在 " + name.length() + " 个字）");
        }
        if (!name.isEmpty()) {
            String conflict = nameConflict(userId, name);
            if (conflict != null) {
                problems.add(conflict);
            }
        }
        long filled = rows.stream().filter(x -> !x.name().isBlank() && positive(x.grams())).count();
        if (filled < MIN_INGREDIENTS) {
            problems.add("食材至少要 " + MIN_INGREDIENTS + " 样，现在填好了 " + filled + " 样");
        }
        if (hasHalfFilledRow(rows)) {
            problems.add("有食材没填克数（克数是算营养的唯一依据）");
        }
        if (steps.size() < MIN_STEPS) {
            problems.add("做法至少要 " + MIN_STEPS + " 条，现在有 " + steps.size() + " 条");
        }
        if (r.calories() <= 0) {
            problems.add("算不出热量：食材都没对上食材库的成分表"
                    + (r.unresolved().isEmpty() ? "" : "（没对上：" + String.join("、", r.unresolved()) + "）"));
        }

        // ---- 提醒：不挡提交，但得让用户知道数字的成色 ----
        if (r.calories() > 0 && !r.trustable()) {
            warnings.add(String.format("只有 %.0f%% 的克重对上了成分表，热量偏低，只能当参考",
                    r.coverage() * 100));
        }
        if (!r.unresolved().isEmpty()) {
            warnings.add("没对上成分表的食材：" + String.join("、", r.unresolved())
                    + "（会记进候选食材表，别人再用几次就能转正）");
        }

        return new Preview(servings, r.calories(), r.proteinGram(), r.fatGram(), r.carbGram(),
                r.fiberGram(), r.sodiumMg(), r.coverage(), r.trustable(),
                r.resolvedItems(), r.totalItems(), r.unresolved(), nutritionCalculator.describe(r),
                rows, problems, warnings, problems.isEmpty());
    }

    // ==================================================================
    // 落库（草稿）
    // ==================================================================

    /**
     * 落库成草稿，返回菜品 ID。
     *
     * <p>主表 + 明细表在同一个事务里：不能出现"菜品写进去了、食材没写"的半截数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(long userId, ManualDishRequest request) {
        Preview preview = preview(userId, request);
        if (!preview.problems().isEmpty()) {
            // 一次把问题说全（join 起来），别让用户改一条提交一次
            throw new BusinessException(String.join("；", preview.problems()));
        }
        ManualDishRequest req = request;
        List<ManualDishRequest.Item> rows = rowsOf(req);
        List<String> steps = validSteps(req);

        Dish dish = new Dish();
        dish.setName(nameOf(req));
        // 归一化键与 AI 那条路共用同一个方法：同一个重名列，两条路必须算出同样的键
        dish.setNameKey(DishDraftWriter.normalizeName(dish.getName()));
        // ★ 菜品来源 = 用户自定义（前端 DishCard/详情页已经有 1/2/3 的文案，不用改展示层）
        dish.setSource(SOURCE_USER);
        dish.setCreatorId(userId);
        // ★ 草稿态：先落库，能不能进找菜库由 tryAutoPublish 决定
        dish.setStatus(DishPublishService.STATUS_DRAFT);
        dish.setBaseServing(preview.servings());
        dish.setTasteTag(req.tasteTags() == null || req.tasteTags().isEmpty()
                ? null : String.join(",", req.tasteTags()));
        dish.setSummary(blankToNull(req.summary()));
        dish.setDifficulty(req.difficulty());
        dish.setPrepTimeMin(req.prepTimeMin());
        dish.setCookTimeMin(req.cookTimeMin());
        dish.setStepJson(toSteps(steps));
        dish.setSeasonings(clean(req.seasonings()));
        dish.setEquipment(clean(req.equipment()));
        dish.setChefTips(req.chefTips() == null || req.chefTips().isBlank()
                ? null : List.of(req.chefTips().trim()));

        // ---- 营养：全部来自本地成分表计算 ----
        // 手写菜谱没有"AI 估算"这条退路，所以：算得出来就按成分表写（来源 2），
        // 覆盖率不够时照样写（这是用户唯一的数据），但 verified=0 ——
        // 详情页会因此显示"本地计算、尚未核验"，而不是假装它是精确值。
        dish.setEnergyKcal(toDecimal(preview.calories()));
        dish.setProteinG(toDecimal(preview.proteinGram()));
        dish.setFatG(toDecimal(preview.fatGram()));
        dish.setCarbG(toDecimal(preview.carbGram()));
        dish.setFiberG(toDecimal(preview.fiberGram()));
        dish.setSodiumMg(toDecimal(preview.sodiumMg()));
        dish.setNutritionSource(DishDraftWriter.SOURCE_LOCAL);
        dish.setNutritionVerified(preview.trustable() ? 1 : 0);

        dishMapper.insert(dish);

        int sort = 0;
        List<String> unknown = new ArrayList<>();
        for (ManualDishRequest.Item row : rows) {
            if (row.name() == null || row.name().isBlank() || !positive(row.grams())) {
                continue;
            }
            Long masterId = resolveId(row);
            if (masterId == null) {
                unknown.add(row.name().trim());
            }
            DishIngredient di = new DishIngredient();
            di.setDishId(dish.getId());
            di.setIngredientId(masterId);
            di.setIngredientName(row.name().trim());
            di.setAmount(toDecimal(row.grams().doubleValue()));
            di.setUnit("g");
            di.setAmountGram(toDecimal(row.grams().doubleValue()));
            // 用户填的第一样视为主料（面板上也这么提示），与 AI 那条路的"第一条是主料"对齐
            di.setMainFlag(sort == 0 ? 1 : 0);
            di.setSortNo(sort++);
            dishIngredientMapper.insert(di);
        }

        ingredientCandidateService.recordUsage(unknown);

        log.info("手写菜谱已落库：dishId={}，name={}，食材 {} 种（表外 {} 种），营养={}，来源=用户自定义",
                dish.getId(), dish.getName(), sort, unknown.size(),
                preview.trustable() ? "按成分表算（已核验）" : "按成分表算（覆盖率不足，未核验）");
        return dish.getId();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 这个菜名该用户是不是已经有一道了。
     *
     * <p>查的是与唯一键同一组条件（{@code creator_id + name_key}），所以库里有一道同名菜
     * ——不管是草稿还是已发布——都会返回一句人话，而不是让用户在数据库唯一键上报错。
     *
     * <p>两个刻意的宽松：
     * <ul>
     *   <li>查库失败只记日志、返回 null（不拦）。试算是"边填边看"的接口，
     *       不能因为数据库抖一下就整屏报错；真撞键了还有 {@code DuplicateKeyException} 兜底。</li>
     *   <li>实体上有 {@code @TableLogic}，框架会给查询自动加 {@code deleted = 0}，
     *       而唯一键<b>不</b>区分逻辑删除 —— 也就是"删掉的同名菜仍占着这个名字"。
     *       这种残留情况交给兜底处理器提示"已经有一道同名的菜了"，这里不重复猜。</li>
     * </ul>
     */
    private String nameConflict(Long userId, String name) {
        if (userId == null) {
            return null;
        }
        String key = DishDraftWriter.normalizeName(name);
        try {
            Long duplicated = dishMapper.selectCount(Wrappers.<Dish>lambdaQuery()
                    .eq(Dish::getCreatorId, userId)
                    .eq(Dish::getNameKey, key));
            if (duplicated != null && duplicated > 0) {
                return "菜名「" + name + "」你已经有一道了（在「我的菜品」里），换个名字再传";
            }
        } catch (Exception e) {
            log.warn("重名检查失败，本次跳过：userId={}，nameKey={}，err={}", userId, key, e.toString());
        }
        return null;
    }

    /**
     * 这一行食材对应哪个主数据 ID。
     *
     * <p>顺序：前端传的 ID（从列表里选的） → 目录按名字归一。
     * 传的 ID 要在目录里查得到才认 —— 否则一个脏 ID 会绕过归一化
     * 直接写进明细表，变成"看起来挂了主数据、其实查不到成分表"的鬼数据。
     */
    private Long resolveId(ManualDishRequest.Item item) {
        if (item == null) {
            return null;
        }
        if (item.ingredientId() != null && ingredientDirectory.nameOf(item.ingredientId()) != null) {
            return item.ingredientId();
        }
        if (item.name() == null || item.name().isBlank()) {
            return null;
        }
        return ingredientDirectory.resolveId(item.name().trim());
    }

    private List<ManualDishRequest.Item> rowsOf(ManualDishRequest req) {
        List<ManualDishRequest.Item> rows = new ArrayList<>();
        if (req == null || req.ingredients() == null) {
            return rows;
        }
        for (ManualDishRequest.Item item : req.ingredients()) {
            if (item == null || (isBlank(item.name()) && !positive(item.grams()))) {
                continue;   // 面板上"加了但没填"的空行
            }
            rows.add(new ManualDishRequest.Item(
                    item.name() == null ? "" : item.name().trim(), item.ingredientId(), item.grams()));
        }
        return rows;
    }

    /** 有名字却没克数（或反过来）的行 —— 这种行不能算数，得明确告诉用户 */
    private boolean hasHalfFilledRow(List<PreviewItem> rows) {
        for (PreviewItem row : rows) {
            boolean hasName = !row.name().isBlank();
            boolean hasGram = positive(row.grams());
            if (hasName != hasGram) {
                return true;
            }
        }
        return false;
    }

    private List<String> validSteps(ManualDishRequest req) {
        List<String> steps = new ArrayList<>();
        if (req == null || req.steps() == null) {
            return steps;
        }
        for (String s : req.steps()) {
            if (s != null && !s.isBlank()) {
                steps.add(s.trim());
            }
        }
        return steps;
    }

    /** 步骤序号由系统重排：用户拖动/删行之后，序号不能是用户填的那个 */
    private List<DishStep> toSteps(List<String> steps) {
        List<DishStep> list = new ArrayList<>();
        int no = 1;
        for (String s : steps) {
            list.add(new DishStep(no++, s));
        }
        return list;
    }

    private List<String> clean(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                list.add(v.trim());
            }
        }
        return list.isEmpty() ? null : list;
    }

    private String nameOf(ManualDishRequest req) {
        return req == null || req.name() == null ? "" : req.name().trim();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 保留一位小数：成分表本身是参考值，多留几位是假精度 */
    private BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
