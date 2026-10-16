package com.apesource.dishservice.service;

import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食材主数据目录（内存缓存）。
 *
 * <p>它一次性回答三类问题，这三类问题原本散落在各处、各答各的：
 * <ol>
 *   <li><b>这个名字对应哪个食材？</b>（{@link #resolveId}）——
 *       自由输入的「西红柿」「tomato」要能归一到主数据「番茄」的 ID；
 *       归不上的才是真正的"表外食材"，走名字召回。</li>
 *   <li><b>这个 ID 叫什么？</b>（{@link #nameOf}）——
 *       ES 文档里存的是 ID 数组，但"还缺哪几样主料"必须给用户看名字。</li>
 *   <li><b>它算主料还是常备调料？</b>（{@link #isSeasoning} / {@link #isSeasoningName}）——
 *       满足率与缺料数只在<b>主料</b>上计算。菜谱要求"盐"而用户没勾盐，
 *       不该被算成"缺一样料"：常备调料默认家里就有。</li>
 * </ol>
 *
 * <p><b>为什么用 category='调料' 而不是按名字猜：</b>
 * 校验器里确实有一份"名字里带油/酱/醋就按调料处理"的启发式
 * （见 ai-service 的 {@code validator.PER_PERSON_GRAM_BY_CATEGORY}），那是为了给用量封顶、宁可宽松；
 * 而这里是决定"要不要判你缺料"，判错了用户会直接看到荒谬的结论
 * （比如"你还缺：盐"），所以必须用数据里的明确类目。
 *
 * <p><b>为什么缓存而不是每次查库：</b>
 * 每次搜索都会为每条候选菜做几十次分类判断，逐次查库是不可接受的。
 * 主数据只有几十到几千行，全量放进内存完全放得下。
 *
 * <p><b>一致性</b>：{@link #refresh} 由定时任务兜底重载（默认 10 分钟），
 * 并且候选食材转正后会主动调一次 —— 否则刚收录的食材要等下一个周期才认得出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngredientDirectory {

    /** 常备调料的类目值（对应 {@code t_ingredient.category}） */
    public static final String CATEGORY_SEASONING = "调料";

    private final IngredientMapper ingredientMapper;

    private volatile Map<Long, String> nameById = Map.of();

    /** 归一化键 → 主数据ID。键包含正式名与全部别名 */
    private volatile Map<String, Long> idByKey = Map.of();

    private volatile Set<Long> seasoningIds = Set.of();

    private volatile Set<String> seasoningKeys = Set.of();

    /** 主数据 ID → 类目（蔬菜/肉类/主食/调料…），给"用量上限按类别定"提供依据 */
    private volatile Map<Long, String> categoryById = Map.of();

    /**
     * 主数据全量行。
     *
     * <p>目录自己只用到名字/类目/别名，但 {@link #snapshot()} 要导出营养六项
     * （喂给 Python ai-service 做本地营养计算），所以把整行也缓存一份 ——
     * 不然每次生成请求都要额外查一次库。
     */
    private volatile Map<Long, Ingredient> rowsById = Map.of();

    @PostConstruct
    void init() {
        refresh();
    }

    /**
     * 重载目录。
     *
     * <p><b>绝不抛异常</b>：它可能在任何一次请求路径上被触发，
     * 目录加载失败应该退化成"这次归一化不了"，而不是让整个搜索 500。
     * 保留上一份缓存继续用 —— 旧数据也比没有数据强。
     */
    @Scheduled(initialDelayString = "${es.ingredient-dir.refresh-ms:600000}",
            fixedDelayString = "${es.ingredient-dir.refresh-ms:600000}")
    public void refresh() {
        try {
            List<Ingredient> all = ingredientMapper.selectList(null);

            Map<Long, String> names = new HashMap<>(all.size() * 2);
            Map<Long, String> categories = new HashMap<>(all.size() * 2);
            Map<String, Long> keys = new HashMap<>(all.size() * 4);
            Set<Long> seasonings = new HashSet<>();
            Map<String, Long> seasoningKeyMap = new HashMap<>();
            Map<Long, Ingredient> rows = new HashMap<>(all.size() * 2);
            List<String> collisions = new ArrayList<>();

            // 第一遍：正式名 + 类目 + 调料集合。
            // 名字之间不会互相冲突（t_ingredient.name 是唯一索引），
            // 冲突只可能出现在"别名撞上别的食材的正式名"上。
            for (Ingredient ing : all) {
                if (ing == null || ing.getId() == null) {
                    continue;
                }
                rows.put(ing.getId(), ing);
                names.put(ing.getId(), ing.getName());
                categories.put(ing.getId(), ing.getCategory());
                putKey(keys, ing.getName(), ing.getId());

                if (CATEGORY_SEASONING.equals(ing.getCategory())) {
                    seasonings.add(ing.getId());
                    putKey(seasoningKeyMap, ing.getName(), ing.getId());
                }
            }

            // 第二遍：别名。
            // ★ 顺序很重要：正式名一定比别名更具体，所以别名统一后登记、一律让位。
            // 数据里真的存在这种冲突（「五花肉」既是自己的正式名、又是「猪肉」的别名），
            // 如果混在一遍里登记，结果就取决于数据库返回行的顺序 ——
            // 同一个名字有时归一到甲、有时归一到乙，缺料判断和营养计算跟着抖。
            for (Ingredient ing : all) {
                if (ing == null || ing.getId() == null || ing.getAlias() == null
                        || ing.getAlias().isBlank()) {
                    continue;
                }
                // alias 是逗号分隔的多个别名（"西红柿,tomato"），来自 t_ingredient.alias
                for (String alias : ing.getAlias().split("[,，]")) {
                    String hit = putKey(keys, alias, ing.getId());
                    if (hit != null) {
                        collisions.add(hit);
                    }
                    if (CATEGORY_SEASONING.equals(ing.getCategory())) {
                        putKey(seasoningKeyMap, alias, ing.getId());
                    }
                }
            }

            this.nameById = Map.copyOf(names);
            this.idByKey = Map.copyOf(keys);
            this.seasoningIds = Set.copyOf(seasonings);
            this.seasoningKeys = Set.copyOf(seasoningKeyMap.keySet());
            this.categoryById = Map.copyOf(categories);
            this.rowsById = Map.copyOf(rows);
            if (collisions.isEmpty()) {
                log.info("食材目录已加载：{} 种食材（其中常备调料 {} 种），{} 个可归一化名称",
                        names.size(), seasonings.size(), keys.size());
            } else {
                // 不抛异常、不阻断：冲突按"正式名优先"处理，但必须让人看见，
                // 否则同一道菜的营养会因为别名归属而悄悄算错
                log.warn("食材目录已加载：{} 种食材（其中常备调料 {} 种），{} 个可归一化名称；"
                                + "有 {} 处名字冲突（已按「正式名优先、别名让位」处理，建议整理 t_ingredient.alias）：{}",
                        names.size(), seasonings.size(), keys.size(), collisions.size(), collisions);
            }
        } catch (Exception e) {
            log.warn("食材目录加载失败，继续使用上一份缓存（{} 条）：{}", nameById.size(), e.getMessage());
        }
    }

    /**
     * 把用户写的名字归一化到主数据 ID。
     *
     * @return 主数据ID；认不出来（真的是表外食材）返回 null
     */
    public Long resolveId(String rawName) {
        if (rawName == null) {
            return null;
        }
        return idByKey.get(key(rawName));
    }

    /** ID → 正式名。查不到返回 null（调用方自己决定怎么兜底） */
    public String nameOf(Long id) {
        return id == null ? null : nameById.get(id);
    }

    /** 这个 ID 是不是常备调料 */
    public boolean isSeasoning(Long id) {
        return id != null && seasoningIds.contains(id);
    }

    /** 这个名字（或别名）是不是常备调料 */
    public boolean isSeasoningName(String rawName) {
        return rawName != null && seasoningKeys.contains(key(rawName));
    }

    /**
     * 食材类目（蔬菜 / 肉类 / 主食 / 调料 …）。
     *
     * <p>校验器拿它给"合理用量上限"分档：同样 500 克，对青菜是正常、
     * 对调料就是明显的编造值 —— 一个固定上限做不到这件事。
     *
     * @return 类目名；目录里没有这个 ID（表外食材）时返回 null
     */
    public String categoryOf(Long id) {
        return id == null ? null : categoryById.get(id);
    }

    /** 当前缓存的食材数量，供健康检查/日志用 */
    public int size() {
        return nameById.size();
    }

    /**
     * 导出主数据快照（喂给 Python ai-service 的"随请求注入"）。
     *
     * <p>ai-service 是无状态计算服务、不连业务库：它做食材归一（别名匹配）、
     * 用量分档（类目）、本地营养计算（六项成分）全靠这份快照。只有几十到几百行，
     * 每请求带一份的成本可以忽略，换来的是零缓存一致性问题 ——
     * 食材转正后下一个请求就认得出来。
     *
     * <p>快照与目录同源同刷新：{@link #refresh()} 失败时保留上一份，
     * 行为与目录本身一致（旧数据也比没有数据强）。
     */
    public List<IngredientSnapshot> snapshot() {
        List<IngredientSnapshot> out = new ArrayList<>(rowsById.size());
        for (Ingredient ing : rowsById.values()) {
            out.add(new IngredientSnapshot(ing.getId(), ing.getName(), ing.getAlias(),
                    ing.getCategory(), ing.getCalorie(), ing.getProtein(), ing.getFat(),
                    ing.getCarb(), ing.getFiber(), ing.getSodium()));
        }
        return out;
    }

    /**
     * 全部主数据的<b>正式名</b>。
     *
     * <p>用途是拿它当一份"食材词典"去扫描自由文本（比如做法步骤），
     * 找出"步骤里出现了、但配料表里没有"的食材 —— 模型在步骤里偷偷加料
     * （"再加入牛肉丁"）是最容易被漏掉的一类不合格。
     * 用主数据当词典而不是自己维护关键词，好处是词典跟着数据一起长。
     */
    public Set<String> canonicalNames() {
        return Set.copyOf(new HashSet<>(nameById.values()));
    }

    /**
     * 登记一个"名字/别名 → ID"。
     *
     * <p>先到先得：键已经被登记过（且指向别的食材）时保留先来的那条。
     *
     * @return 撞车时的说明（谁保留了、谁被忽略）；没撞车返回 null
     */
    private String putKey(Map<String, Long> target, String raw, Long id) {
        String k = key(raw);
        if (k == null) {
            return null;
        }
        Long prev = target.putIfAbsent(k, id);
        if (prev != null && !prev.equals(id)) {
            return "「" + raw.trim() + "」→ 保留 " + prev + "，忽略 " + id;
        }
        return null;
    }

    /**
     * 归一化键：去掉所有空白 + 转小写。
     * <p>与 {@code IngredientCandidateService.nameKey} 保持同一套规则 ——
     * 两处用了不同的归一化键，就会出现"候选表认得出、目录认不出"的怪现象。
     *
     * <p>公开给别的包用（校验器要拿它比对"步骤里提到的食材"和"配料表里的食材"），
     * 这样全项目只有一份归一化规则。
     */
    public static String key(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.replaceAll("\\s+", "").toLowerCase();
        return k.isEmpty() ? null : k;
    }
}
