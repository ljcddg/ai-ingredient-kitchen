package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.DishGeneratePayload;
import com.apesource.dishservice.dto.DishStep;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.entity.Dish;
import com.apesource.dishservice.entity.DishIngredient;
import com.apesource.dishservice.mapper.DishIngredientMapper;
import com.apesource.dishservice.mapper.DishMapper;
import com.apesource.dishservice.service.IngredientCandidateService;
import com.apesource.dishservice.service.IngredientDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 把生成出来的草稿写进数据库。
 *
 * <h3>为什么单独拆一个类</h3>
 * {@code @Transactional} 是靠 Spring 的<b>代理</b>实现的。如果这个方法写在
 * {@link DishGenerationExecutor} 里、又由它自己的 {@code execute()} 调用，
 * 那就是"同类内部调用"，<b>压根不经过代理，事务会静默失效</b> ——
 * 不报错、不警告，只是回滚不了。
 *
 * <p>拆出来的另一个好处是职责干净：执行器管"什么时候跑、跑出什么结果"，
 * 这里只管"怎么把结果落进表"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishDraftWriter {

    /** 营养来源：AI 估算 */
    public static final int SOURCE_AI = 1;

    /** 营养来源：按食材成分表本地计算 */
    public static final int SOURCE_LOCAL = 2;

    private final DishMapper dishMapper;
    private final DishIngredientMapper dishIngredientMapper;

    /** P2-2：按成分表本地算营养 */
    private final NutritionCalculator nutritionCalculator;

    /** 主数据里还没有的食材记进候选表，够次数就转正（见 IngredientCandidateService） */
    private final IngredientCandidateService ingredientCandidateService;

    /**
     * 食材归一化目录（P1-1）。
     *
     * <p>这里用它把"不是从列表里选的"食材名字（用户在输入框自由敲的、AI 自己补的）
     * 再归一化一次 —— 名字对得上主数据就挂上主数据 ID，别让同一样东西因为
     * 写法和列表里不一样就变成"表外食材"。
     */
    private final IngredientDirectory ingredientDirectory;

    /**
     * 落库：主表 + 明细表在<b>同一个事务</b>里。
     *
     * <p>不能出现"菜品写进去了、食材没写"的半截数据 ——
     * 那种菜点进去食材清单是空的，用户只会认为程序坏了。
     *
     * @return 新草稿的菜品 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(long userId, DishGeneratePayload payload, DishGenerator.GenerateResult result) {
        DishDraft d = result.draft();

        Dish dish = new Dish();
        dish.setName(d.getName());
        dish.setNameKey(normalizeName(d.getName()));
        dish.setSource(2);
        dish.setCreatorId(userId);
        // ★ 草稿态：只有创建者自己看得见，不进推荐池、也不同步进 ES
        dish.setStatus(0);
        dish.setBaseServing(payload.servings());
        dish.setTasteTag(payload.tastes() == null || payload.tastes().isEmpty()
                ? null : String.join(",", payload.tastes()));
        dish.setSummary(d.getSummary());
        dish.setDifficulty(d.getDifficulty());
        dish.setPrepTimeMin(d.getPrepTimeMin());
        dish.setCookTimeMin(d.getCookTimeMin());
        dish.setSpicyLevel(d.getSpicyLevel());
        dish.setStepJson(toSteps(d.getSteps()));
        dish.setSeasonings(d.getSeasonings());
        dish.setEquipment(d.getEquipment());
        dish.setChefTips(d.getChefTips());
        dish.setSubstitutes(d.getSubstitutes());

        // ---------- 营养：以本地计算为准（说明文档 P2-2）----------
        // 「食材成分表 × 用量 ÷ 人数」算得出来就用它；算不出来（成分表对不齐，
        // 比如用户自由输入的陌生食材）才退回 AI 估算，并如实标注来源 ——
        // 关键是不能让 AI 估算冒充"算出来的值"。
        DishDraft.NutritionDraft n = d.getNutritionPerServing();
        NutritionCalculator.Result local =
                nutritionCalculator.calculate(localItems(d), payload.servings());
        if (local.trustable()) {
            dish.setEnergyKcal(toDecimal(local.calories()));
            dish.setProteinG(toDecimal(local.proteinGram()));
            dish.setFatG(toDecimal(local.fatGram()));
            dish.setCarbG(toDecimal(local.carbGram()));
            dish.setFiberG(toDecimal(local.fiberGram()));
            dish.setSodiumMg(toDecimal(local.sodiumMg()));
            dish.setNutritionSource(SOURCE_LOCAL);
            // 本地算的就是"按成分表核过"的，标记为已核验
            dish.setNutritionVerified(1);
            log.info("营养按成分表本地计算：{}", nutritionCalculator.describe(local));
            if (n != null && n.getCalories() != null && local.calories() > 0) {
                double diff = Math.abs(n.getCalories() - local.calories()) / local.calories();
                log.info("本地值与 AI 估算的偏差：AI {} kcal vs 本地 {} kcal（{}%）",
                        n.getCalories(), Math.round(local.calories()), Math.round(diff * 100));
            }
        } else {
            if (n != null) {
                dish.setEnergyKcal(toDecimal(n.getCalories()));
                dish.setProteinG(toDecimal(n.getProteinGram()));
                dish.setFatG(toDecimal(n.getFatGram()));
                dish.setCarbG(toDecimal(n.getCarbGram()));
                dish.setFiberG(toDecimal(n.getFiberGram()));
                dish.setSodiumMg(toDecimal(n.getSodiumMg()));
            }
            // 来源 1 = AI 估算；0 = 连估算都没有
            dish.setNutritionSource(n == null ? 0 : SOURCE_AI);
            // 本地校验是否通过 —— 用户看不到这个标记，但它是"这道菜营养可不可信"的内部账
            dish.setNutritionVerified(result.nutritionVerified() ? 1 : 0);
            log.info("营养沿用 AI 估算（成分表覆盖不足）：{}", nutritionCalculator.describe(local));
        }

        dishMapper.insert(dish);

        int sort = 0;
        int normalizedByName = 0;
        List<String> unknownIngredients = new ArrayList<>();
        for (DishDraft.DraftIngredient item : d.getIngredients()) {
            if (item == null || item.getName() == null || item.getAmountGram() == null) {
                continue;
            }
            Long masterId = resolveIngredientId(item.getName(), payload);
            if (masterId == null) {
                // 不是从列表里选的（自由输入 / AI 自己补的）：先按目录归一化一次，
                // 别名、正式名、大小写差异都认。挂上主数据 ID 之后它才能被
                // ID 精确召回，营养计算也才查得到成分表
                masterId = ingredientDirectory.resolveId(item.getName());
                if (masterId != null) {
                    normalizedByName++;
                }
            }
            if (masterId == null) {
                // 主数据里还没有它。名字照样会进索引（能被搜到），
                // 同时记进候选表 —— 够次数就转正，之后走 ID 精确召回
                unknownIngredients.add(item.getName());
            }
            DishIngredient di = new DishIngredient();
            di.setDishId(dish.getId());
            di.setIngredientId(masterId);
            di.setIngredientName(item.getName());
            di.setAmount(toDecimal(item.getAmountGram()));
            di.setUnit("g");
            di.setAmountGram(toDecimal(item.getAmountGram()));
            // 第一条视为主料 —— 提示词要求模型按"主料 → 辅料"排序，所以顺序本身就是信息
            di.setMainFlag(sort == 0 ? 1 : 0);
            di.setSortNo(sort++);
            dishIngredientMapper.insert(di);
        }

        // 记候选：内部对每个名字单独 try/catch，写失败不会影响这次落库
        ingredientCandidateService.recordUsage(unknownIngredients);

        log.info("草稿已落库：dishId={}，name={}，食材 {} 种（表外 {} 种、另有 {} 种名字归一到主数据），营养校验={}",
                dish.getId(), dish.getName(), sort, unknownIngredients.size(),
                normalizedByName, result.nutritionVerified());
        return dish.getId();
    }

    /**
     * 找出这个食材在用户输入里对应的主数据 ID。
     *
     * <p>从列表里选的食材有 ID；在输入框里自由敲出来的没有（返回 null），
     * 那这条明细就只靠 {@code ingredientName} 支撑 —— 表结构已经为这种情况留好了位置。
     */
    private Long resolveIngredientId(String name, DishGeneratePayload payload) {
        if (payload.ingredients() == null) {
            return null;
        }
        for (IngredientRef ref : payload.ingredients()) {
            if (ref != null && ref.id() != null && name.equals(ref.name())) {
                return ref.id();
            }
        }
        return null;
    }

    private List<DishStep> toSteps(List<String> steps) {
        List<DishStep> list = new ArrayList<>();
        if (steps == null) {
            return list;
        }
        int no = 1;
        for (String s : steps) {
            list.add(new DishStep(no++, s));
        }
        return list;
    }

    /**
     * 菜名归一化键：目前只去空格，后续可加"去修饰词 + 同义替换"以提升重复检测能力。
     *
     * <p><b>public static</b>：手写菜谱（{@link ManualDishWriter}）也要写 {@code name_key}，
     * 同一个重名列两条路必须算出同一个键 —— 复制一份实现迟早会改歪一条。
     */
    public static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }

    private BigDecimal toDecimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 本地营养计算保留一位小数：成分表本身是参考值，多留几位是假精度 */
    private BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 把草稿里的食材整理成"名字 + 克数"。
     *
     * <p>不含调料：草稿里的调料只有名字没有用量，算不了。这是本地计算的口径边界，
     * 已写在 {@link NutritionCalculator} 的注释里。
     */
    private List<NutritionCalculator.Item> localItems(DishDraft draft) {
        List<NutritionCalculator.Item> items = new ArrayList<>();
        if (draft.getIngredients() == null) {
            return items;
        }
        for (DishDraft.DraftIngredient item : draft.getIngredients()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            items.add(new NutritionCalculator.Item(item.getName(),
                    item.getAmountGram() == null ? null : BigDecimal.valueOf(item.getAmountGram())));
        }
        return items;
    }
}
