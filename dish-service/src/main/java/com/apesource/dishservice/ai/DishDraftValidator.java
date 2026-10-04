package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.service.IngredientDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成的草稿要过一遍机器可判的规则。
 *
 * <p>这是"约束输出"三道防线里的第三道：
 * <ol>
 *   <li>系统提示词（告诉它要什么）</li>
 *   <li>Ollama 的 {@code format=json}（约束解码，保证是合法 JSON）</li>
 *   <li><b>本类</b>（保证字段齐全、类型对、取值合理，且真的满足了用户条件）</li>
 * </ol>
 *
 * <p>校验失败的返回值会**原样拼进重试提示词**，所以每条信息都必须说清
 * "哪个字段、现在是什么、期望是什么" —— 写"格式错误"四个字对模型毫无帮助。
 *
 * <p>规则与 {@code prompts/dish-generate-system.txt} 里的字段说明一一对应，
 * 改提示词时必须同步改这里，否则会出现"提示词允许但校验拒绝"的诡异现象。
 *
 * <h3>本次新增的四类检查（说明文档第三节）</h3>
 * <ol>
 *   <li><b>用户约束</b>：必用食材有没有用上、禁用食材有没有出现（含步骤里的）、
 *       总耗时是否超标、是否用到了用户没有的厨具；</li>
 *   <li><b>配料表与步骤的一致性</b>：配料表里的食材要在步骤里有用途，
 *       步骤里也不能凭空多出配料表之外的食材；</li>
 *   <li><b>用量按人数与类别</b>：调料和蔬菜不该共用一个上限，
 *       1 人份和 6 人份也不该共用一个上限 —— 同时用它反查"人数是否一致"；</li>
 *   <li><b>食材匹配改成主数据 ID / 别名</b>：原来用字符串包含判断，
 *       会把「鸡蛋面」里的「鸡蛋」当成命中了「鸡蛋」，也会因为用户写「西红柿」
 *       而模型写「番茄」就误判成没用到。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class DishDraftValidator {

    /** 每人份合理用量上限（克），按主数据类目分档 —— 一个固定值管不了所有场景 */
    private static final Map<String, Integer> PER_PERSON_GRAM_BY_CATEGORY = Map.ofEntries(
            Map.entry("蔬菜", 300),
            Map.entry("果香", 300),
            Map.entry("肉类", 250),
            Map.entry("水产", 250),
            Map.entry("主食", 250),
            Map.entry("豆制品", 200),
            Map.entry("蛋奶", 150),
            Map.entry("乳品", 150),
            Map.entry("干货", 100),
            Map.entry("调料", 15),
            Map.entry("其他", 250));

    /** 目录里查不到类目时的默认上限 */
    private static final int DEFAULT_PER_PERSON_GRAM = 250;

    /** 余量系数：模型给的量偏大一点不该判错，超过这个倍数才报 */
    private static final double AMOUNT_SLACK = 1.5;

    /** 调料不随人数线性放大：3 人份也不会放 3 倍的盐 */
    private static final double SEASONING_SERVING_GROWTH = 0.3;

    /** 一份菜"人均主料总量"的合理区间，用来反查人数是否一致 */
    private static final int MIN_MAIN_GRAM_PER_SERVING = 80;

    private static final int MAX_MAIN_GRAM_PER_SERVING = 450;

    /**
     * 单字食材名不参与"步骤里凭空多出食材"的扫描。
     * <p>「盐」「糖」「醋」在中文里太容易作为构词成分出现（糖醋、盐水、醋溜），
     * 报出来的全是误判；它们由"必用/禁用食材"那两条精确检查兜底。
     */
    private static final int MIN_NAME_LEN_FOR_STEP_SCAN = 2;

    /** 明确不该出现在 ingredients 里的东西（提示词已要求不列调料） */
    private static final List<String> SEASONING_KEYS =
            List.of("盐", "糖", "酱油", "生抽", "老抽", "料酒", "醋", "蚝油");

    private final IngredientDirectory directory;

    /**
     * 校验草稿。
     *
     * @param draft 待校验的草稿
     * @param input 本次生成的输入（用户食材、人数、约束）——
     *              约束要一起进来，否则"忌口"这类要求就只有提示词在管，没有任何兜底
     * @return 问题列表；空表示通过
     */
    public List<String> validate(DishDraft draft, DishGenerator.GenerateInput input) {
        return validateDetailed(draft, input).problems();
    }

    /**
     * 分档校验：把「格式问题」和「约束问题」分开给出来。
     *
     * <p><b>为什么要分</b>（说明文档第三节要求"格式合格、约束合格分别记录"）：
     * 格式不合格 = 模型连结构化输出都没写对，该改的是提示词/约束解码；
     * 约束不合格 = 结构没问题但没听用户的话（漏了必用食材、踩了忌口），
     * 该改的是约束表达或换更强的模型。两者混成一个"失败率"，就看不出该往哪使劲。
     *
     * <p>分档口径：
     * <ul>
     *   <li><b>格式</b>：字段有没有、长度/取值在不在合理区间、用量是否离谱、
     *       人数是否与用量自洽、营养是否在区间内；</li>
     *   <li><b>约束</b>：用户给的食材用没用上、必用/禁用食材、耗时上限、可用设备、
     *       以及配料表与步骤的一致性。</li>
     * </ul>
     *
     * @return 全部问题（格式在前、约束在后）+ 其中的约束类问题
     */
    public ValidationResult validateDetailed(DishDraft draft, DishGenerator.GenerateInput input) {
        if (draft == null) {
            return new ValidationResult(List.of("输出为空，没有解析出任何字段"), List.of());
        }

        List<String> format = new ArrayList<>();
        List<String> constraint = new ArrayList<>();

        int servings = Math.max(1, input == null ? 2 : input.servings());
        GenerateConstraints constraints = input == null
                ? GenerateConstraints.none() : input.constraintsOrNone();

        // ---------- 1. 字段格式 ----------
        checkText(format, "name", draft.getName(), 1, 12);
        checkText(format, "summary", draft.getSummary(), 20, 40);
        checkRange(format, "prepTimeMin", draft.getPrepTimeMin(), 2, 60);
        checkRange(format, "cookTimeMin", draft.getCookTimeMin(), 5, 120);
        checkRange(format, "difficulty", draft.getDifficulty(), 1, 3);
        checkRange(format, "spicyLevel", draft.getSpicyLevel(), 0, 3);

        checkListSize(format, "steps", draft.getSteps(), 4, 8);
        if (draft.getSteps() != null) {
            for (int i = 0; i < draft.getSteps().size(); i++) {
                checkText(format, "steps[" + (i + 1) + "]", draft.getSteps().get(i), 15, 50);
            }
        }
        checkListSize(format, "seasonings", draft.getSeasonings(), 1, 4);
        checkListSize(format, "equipment", draft.getEquipment(), 1, 3);
        checkListSize(format, "chefTips", draft.getChefTips(), 2, 3);
        checkListSize(format, "dietTags", draft.getDietTags(), 1, 3);

        // ---------- 2. 配料表与用量（按人数与类别） ----------
        checkIngredients(format, draft, servings);

        // ---------- 3. 营养 ----------
        checkNutrition(format, draft.getNutritionPerServing());

        // ---------- 4. 用户食材与约束 ----------
        checkUserIngredientsUsed(constraint, draft, input);
        checkMustUse(constraint, draft, constraints);
        checkExcluded(constraint, draft, constraints);
        checkCookTime(constraint, draft, constraints);
        checkEquipment(constraint, draft, constraints);

        // ---------- 5. 配料表与步骤的一致性 ----------
        checkIngredientsUsedInSteps(constraint, draft, input, constraints);
        checkNoStrayIngredientsInSteps(constraint, draft);

        List<String> all = new ArrayList<>(format);
        all.addAll(constraint);
        return new ValidationResult(all, constraint);
    }

    /**
     * 校验结果。
     *
     * @param problems           全部问题（格式在前、约束在后）—— 会原样拼进重试提示词
     * @param constraintProblems 其中的约束类问题（{@code problems} 的子集）
     */
    public record ValidationResult(List<String> problems, List<String> constraintProblems) {

        /** 格式合格（没有格式类问题） */
        public boolean formatOk() {
            return problems.size() <= constraintProblems.size();
        }

        /** 约束合格（没有约束类问题） */
        public boolean constraintOk() {
            return constraintProblems.isEmpty();
        }

        /** 全部通过 */
        public boolean passed() {
            return problems.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 配料表与用量

    private void checkIngredients(List<String> problems, DishDraft draft, int servings) {
        List<DishDraft.DraftIngredient> list = draft.getIngredients();
        if (list == null || list.isEmpty()) {
            problems.add("ingredients 缺失或为空");
            return;
        }

        int mainGramTotal = 0;
        for (int i = 0; i < list.size(); i++) {
            DishDraft.DraftIngredient item = list.get(i);
            String where = "ingredients[" + (i + 1) + "]";
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                problems.add(where + ".name 缺失");
                continue;
            }
            String name = item.getName().trim();
            Integer gram = item.getAmountGram();
            if (gram == null || gram <= 0) {
                problems.add(where + "(" + name + ").amountGram 不是正整数");
                continue;
            }

            // 用量钳制 —— 模型编数字是常态，这里是拦它的地方
            if (name.contains("水") || name.contains("高汤") || name.contains("清汤")) {
                problems.add("ingredients 里不该出现不含营养的液体：" + name);
                continue;
            }

            boolean seasoning = isSeasoning(name);
            int limit = gramLimit(name, seasoning, servings);
            if (gram > limit) {
                problems.add(String.format("%s(%s) 用量 %dg 明显过大：按 %d 人份与该食材类别，应不超过 %dg",
                        where, name, gram, servings, limit));
            }
            if (!seasoning) {
                mainGramTotal += gram;
            }
        }

        // 人数一致性：主料总量落在"人均 80~450g"之间。
        // 说明文档要求校验"人数是否一致"，而草稿里并没有 servings 字段 ——
        // 让模型自己回填一个数字等于让它自证，不如用它给出的用量反推：
        // 写"2 人份"却只给 150 克主料、或者给 3 公斤，都是自相矛盾。
        if (mainGramTotal > 0) {
            int min = MIN_MAIN_GRAM_PER_SERVING * servings;
            int max = MAX_MAIN_GRAM_PER_SERVING * servings;
            if (mainGramTotal < min || mainGramTotal > max) {
                problems.add(String.format("主料总量 %dg 与 %d 人份不匹配（人均约 %d~%dg，合计应在 %d~%dg 之间）",
                        mainGramTotal, servings, MIN_MAIN_GRAM_PER_SERVING, MAX_MAIN_GRAM_PER_SERVING, min, max));
            }
        }
    }

    /** 按"食材类别 × 人数"算这道菜里该食材的用量上限 */
    private int gramLimit(String name, boolean seasoning, int servings) {
        int perPerson = seasoning ? PER_PERSON_GRAM_BY_CATEGORY.get("调料") : perPersonGram(name);
        double scale = seasoning
                ? 1 + SEASONING_SERVING_GROWTH * Math.max(0, servings - 1)
                : Math.max(1, servings);
        return (int) Math.ceil(perPerson * scale * AMOUNT_SLACK);
    }

    private int perPersonGram(String name) {
        String category = directory.categoryOf(directory.resolveId(name));
        Integer byCategory = category == null ? null : PER_PERSON_GRAM_BY_CATEGORY.get(category);
        return byCategory != null ? byCategory : DEFAULT_PER_PERSON_GRAM;
    }

    /**
     * 是不是调料。
     * <p>先用主数据的 {@code category='调料'}（权威），查不到再退回名字启发式
     * （表外食材、或模型自造的词）—— 两个都查不到就按主料处理，宁可严一点。
     */
    private boolean isSeasoning(String name) {
        if (directory.isSeasoningName(name)) {
            return true;
        }
        if (name.endsWith("油") || name.endsWith("酱") || name.endsWith("汁")) {
            return true;
        }
        return SEASONING_KEYS.stream().anyMatch(name::contains);
    }

    // ------------------------------------------------------------------ 食材匹配（ID / 别名）

    /**
     * 判断草稿里的某个名字是不是目标食材。
     *
     * <p>三条路，任一命中即可：
     * <ol>
     *   <li>归一化后名字相同（「番茄」=「番茄」）；</li>
     *   <li>两边都能解析成同一个主数据 ID（用户写「西红柿」、模型写「番茄」）；</li>
     *   <li>目标只给了名字时，现场解析它的 ID 再比。</li>
     * </ol>
     * 这就是把原来的 {@code text.contains(name)} 换掉的原因：
     * 那个写法既会误判（「鸡蛋面」包含「鸡蛋」），也会漏判（别名对不上）。
     */
    private boolean isSameIngredient(String candidate, Long targetId, String targetName) {
        String candidateKey = IngredientDirectory.key(candidate);
        String targetKey = IngredientDirectory.key(targetName);
        if (candidateKey == null) {
            return false;
        }
        if (targetKey != null && candidateKey.equals(targetKey)) {
            return true;
        }
        Long wantId = targetId != null ? targetId : (targetKey == null ? null : directory.resolveId(targetKey));
        Long candidateId = directory.resolveId(candidate);
        return wantId != null && wantId > 0 && wantId.equals(candidateId);
    }

    /** 草稿里"可食用的名字"：配料表 + 调料表 */
    private List<String> allEdibleNames(DishDraft draft) {
        List<String> names = new ArrayList<>(draftIngredientNames(draft));
        if (draft.getSeasonings() != null) {
            for (String s : draft.getSeasonings()) {
                if (s != null && !s.isBlank()) {
                    names.add(s.trim());
                }
            }
        }
        return names;
    }

    private List<String> draftIngredientNames(DishDraft draft) {
        List<String> names = new ArrayList<>();
        if (draft.getIngredients() != null) {
            for (DishDraft.DraftIngredient i : draft.getIngredients()) {
                if (i != null && i.getName() != null && !i.getName().isBlank()) {
                    names.add(i.getName().trim());
                }
            }
        }
        return names;
    }

    /** 草稿里是否用到了这个食材（配料表或调料表里出现了它） */
    private boolean mentions(DishDraft draft, Long id, String name) {
        for (String candidate : allEdibleNames(draft)) {
            if (isSameIngredient(candidate, id, name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 用户食材与约束

    /** 用户给的食材一个都不能少 —— 这是这个功能的根本承诺 */
    private void checkUserIngredientsUsed(List<String> problems, DishDraft draft, DishGenerator.GenerateInput input) {
        if (input == null || input.ingredients() == null || input.ingredients().isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (IngredientRef ref : input.ingredients()) {
            if (ref == null || ref.name() == null || ref.name().isBlank()) {
                continue;
            }
            if (!mentions(draft, ref.id(), ref.name().trim())) {
                missing.add(ref.name().trim());
            }
        }
        if (!missing.isEmpty()) {
            problems.add("没有用上用户提供的食材：" + String.join("、", missing));
        }
    }

    private void checkMustUse(List<String> problems, DishDraft draft, GenerateConstraints c) {
        List<String> missing = new ArrayList<>();
        for (Long id : c.mustUseIdSet()) {
            String name = directory.nameOf(id);
            if (!mentions(draft, id, name)) {
                missing.add(name != null ? name : ("食材#" + id));
            }
        }
        for (String name : c.mustUseNames()) {
            if (!mentions(draft, directory.resolveId(name), name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            problems.add("用户要求必用的食材没有出现在配料表里：" + String.join("、", missing));
        }
    }

    /**
     * 禁用食材：配料表、调料表、<b>以及步骤正文</b>里都不许出现。
     * <p>步骤也要查，是因为"过敏源写进做法"同样是事故：配料表里没有花生，
     * 步骤里却写"用花生油炒香"，对过敏的人来说没有区别。
     */
    private void checkExcluded(List<String> problems, DishDraft draft, GenerateConstraints c) {
        Set<String> hit = new LinkedHashSet<>();
        String steps = String.join("\n", nz(draft.getSteps()));

        for (Long id : c.excludeIdSet()) {
            String name = directory.nameOf(id);
            String label = name != null ? name : ("食材#" + id);
            if (mentions(draft, id, name) || (name != null && steps.contains(name))) {
                hit.add(label);
            }
        }
        for (String name : c.excludeNames()) {
            if (mentions(draft, directory.resolveId(name), name) || steps.contains(name)) {
                hit.add(name);
            }
        }
        if (!hit.isEmpty()) {
            problems.add("出现了用户禁用的食材：" + String.join("、", hit));
        }
    }

    private void checkCookTime(List<String> problems, DishDraft draft, GenerateConstraints c) {
        if (c.maxCookTimeMin() == null) {
            return;
        }
        int prep = draft.getPrepTimeMin() == null ? 0 : draft.getPrepTimeMin();
        int cook = draft.getCookTimeMin() == null ? 0 : draft.getCookTimeMin();
        int total = prep + cook;
        if (total > c.maxCookTimeMin()) {
            problems.add(String.format("总耗时 %d 分钟超过用户要求的 %d 分钟（备菜 %d + 下锅 %d）",
                    total, c.maxCookTimeMin(), prep, cook));
        }
    }

    private void checkEquipment(List<String> problems, DishDraft draft, GenerateConstraints c) {
        Set<String> available = c.equipmentNames();
        if (available.isEmpty()) {
            return;
        }
        List<String> unavailable = new ArrayList<>();
        for (String eq : nz(draft.getEquipment())) {
            if (eq == null || eq.isBlank()) {
                continue;
            }
            String required = eq.trim();
            boolean ok = available.stream().anyMatch(a -> sameEquipment(a, required));
            if (!ok) {
                unavailable.add(required);
            }
        }
        if (!unavailable.isEmpty()) {
            problems.add("用到了用户没有的厨具：" + String.join("、", unavailable)
                    + "（用户只有：" + String.join("、", available) + "）");
        }
    }

    /**
     * 厨具名做宽松匹配。
     * <p>「锅」和「炒锅」、「蒸锅」和「蒸屉」这类包含关系不该触发重试 ——
     * 为用词差异反复重试，浪费的是十几秒的生成时间，换不来任何质量提升。
     * 真正要拦的是"用户只有炒锅、菜谱却要烤箱"这种硬缺口。
     */
    private boolean sameEquipment(String available, String required) {
        return available.equals(required) || available.contains(required) || required.contains(available);
    }

    // ------------------------------------------------------------------ 配料表与步骤一致性

    /**
     * 配料表里的食材，要在步骤里真的有用途。
     *
     * <p>说明文档第三节明确要求这一条：模型的典型偷懒是"配料表列齐了，
     * 步骤里只用其中两样" —— 用户照着做会发现某样食材根本没有下锅时机。
     *
     * <p>必用食材（用户点名要的）必查；用户提供的其他食材只在它已经进了配料表时查
     * （没进配料表的，前面"没有用上用户提供的食材"已经报过了，不重复报）。
     */
    private void checkIngredientsUsedInSteps(List<String> problems, DishDraft draft,
                                             DishGenerator.GenerateInput input, GenerateConstraints c) {
        String text = String.join("\n", nz(draft.getSteps()));
        if (text.isBlank()) {
            return;
        }

        List<String> mustUseUnused = new ArrayList<>();
        for (Long id : c.mustUseIdSet()) {
            String name = directory.nameOf(id);
            if (name != null && !mentionedInText(text, name)) {
                mustUseUnused.add(name);
            }
        }
        for (String name : c.mustUseNames()) {
            if (!mentionedInText(text, name)) {
                mustUseUnused.add(name);
            }
        }
        if (!mustUseUnused.isEmpty()) {
            problems.add("用户要求必用的食材只出现在配料表里、步骤中没有用到：" + String.join("、", mustUseUnused));
        }

        if (input == null || input.ingredients() == null) {
            return;
        }
        List<String> unused = new ArrayList<>();
        for (IngredientRef ref : input.ingredients()) {
            if (ref == null || ref.name() == null || ref.name().isBlank()) {
                continue;
            }
            String name = ref.name().trim();
            if (mustUseUnused.contains(name) || !mentions(draft, ref.id(), name)) {
                continue;
            }
            if (!mentionedInText(text, name)) {
                unused.add(name);
            }
        }
        if (!unused.isEmpty()) {
            problems.add("配料表里的这些食材在步骤中没有出现：" + String.join("、", unused));
        }
    }

    /**
     * 步骤里是否真的提到了这个食材（允许用别名或它的正式名）。
     * <p>用户写「西红柿」、步骤里说「番茄」，算提到了。
     */
    private boolean mentionedInText(String text, String name) {
        if (text.contains(name)) {
            return true;
        }
        Long id = directory.resolveId(name);
        String canonical = id == null ? null : directory.nameOf(id);
        return canonical != null && text.contains(canonical);
    }

    /**
     * 步骤里不能凭空多出配料表之外的食材。
     *
     * <p>做法是用主数据当"食材词典"去扫步骤正文：词典里的名字出现在步骤里、
     * 却不在配料表/调料表里（也没被别名对上），就说明模型偷偷加了料。
     * 单字名（盐/糖/醋）不参与，理由见 {@link #MIN_NAME_LEN_FOR_STEP_SCAN}。
     */
    private void checkNoStrayIngredientsInSteps(List<String> problems, DishDraft draft) {
        String text = String.join("\n", nz(draft.getSteps()));
        if (text.isBlank()) {
            return;
        }

        Set<String> listedKeys = new LinkedHashSet<>();
        Set<Long> listedIds = new LinkedHashSet<>();
        for (String name : allEdibleNames(draft)) {
            listedKeys.add(IngredientDirectory.key(name));
            Long id = validId(directory.resolveId(name));
            if (id != null) {
                listedIds.add(id);
            }
        }

        Set<String> stray = new LinkedHashSet<>();
        for (String known : directory.canonicalNames()) {
            if (known == null || known.length() < MIN_NAME_LEN_FOR_STEP_SCAN) {
                continue;
            }
            if (!text.contains(known)) {
                continue;
            }
            if (listedKeys.contains(IngredientDirectory.key(known))) {
                continue;
            }
            Long id = validId(directory.resolveId(known));
            if (id != null && listedIds.contains(id)) {
                continue;
            }
            stray.add(known);
        }
        if (!stray.isEmpty()) {
            problems.add("步骤里出现了配料表之外的食材：" + String.join("、", stray));
        }
    }

    /**
     * 把"解析食材 ID"的结果规整成可信的值。
     *
     * <p>主数据 ID 是雪花 ID，永远是正数，所以**非正数一律当作"没解析出来"**。
     * 这一层不是洁癖：拿 mock 做单测时，未打桩的 {@code Long} 方法默认返回
     * <b>0 而不是 null</b>，于是"目录里没有的食材"会带着一个 ID 0 混进集合，
     * 和另一个同样返回 0 的食材被判成"同一个" —— 表现为校验静默放过。
     * 这种坑只在测试里出现，但修在实现侧几乎零成本，顺带也让实现更严谨。
     */
    private Long validId(Long id) {
        return id != null && id > 0 ? id : null;
    }

    // ------------------------------------------------------------------ 营养

    private void checkNutrition(List<String> problems, DishDraft.NutritionDraft n) {
        if (n == null) {
            problems.add("nutritionPerServing 缺失");
            return;
        }
        checkRange(problems, "nutritionPerServing.calories", n.getCalories(), 0, 800);
        nonNegative(problems, "nutritionPerServing.proteinGram", n.getProteinGram());
        nonNegative(problems, "nutritionPerServing.fatGram", n.getFatGram());
        nonNegative(problems, "nutritionPerServing.carbGram", n.getCarbGram());
        nonNegative(problems, "nutritionPerServing.fiberGram", n.getFiberGram());
        checkRange(problems, "nutritionPerServing.sodiumMg", n.getSodiumMg(), 200, 1500);
    }

    // ------------------------------------------------------------------ 基础断言

    private void checkText(List<String> problems, String field, String value, int min, int max) {
        if (value == null || value.isBlank()) {
            problems.add(field + " 缺失");
            return;
        }
        int len = value.trim().length();
        if (len < min || len > max) {
            problems.add(field + " 长度 " + len + " 不在 " + min + "~" + max + " 之间");
        }
    }

    private void checkRange(List<String> problems, String field, Integer value, int min, int max) {
        if (value == null) {
            problems.add(field + " 缺失");
            return;
        }
        if (value < min || value > max) {
            problems.add(field + "=" + value + " 不在 " + min + "~" + max + " 之间");
        }
    }

    private void nonNegative(List<String> problems, String field, Integer value) {
        if (value == null) {
            problems.add(field + " 缺失");
            return;
        }
        if (value < 0) {
            problems.add(field + "=" + value + " 不该是负数");
        }
    }

    private void checkListSize(List<String> problems, String field, List<?> list, int min, int max) {
        if (list == null || list.isEmpty()) {
            problems.add(field + " 缺失或为空");
            return;
        }
        if (list.size() < min || list.size() > max) {
            problems.add(field + " 有 " + list.size() + " 项，不在 " + min + "~" + max + " 之间");
        }
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
