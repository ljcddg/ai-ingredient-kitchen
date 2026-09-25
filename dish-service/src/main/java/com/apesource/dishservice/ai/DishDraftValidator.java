package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 生成的草稿要过一遍机器可判的规则。
 *
 * <p>这是"约束输出"三道防线里的第三道：
 * <ol>
 *   <li>系统提示词（告诉它要什么）</li>
 *   <li>Ollama 的 {@code format=json}（约束解码，保证是合法 JSON）</li>
 *   <li><b>本类</b>（保证字段齐全、类型对、取值在合理区间）</li>
 * </ol>
 *
 * <p>校验失败的返回值会**原样拼进重试提示词**，所以每条信息都必须说清
 * "哪个字段、现在是什么、期望是什么" —— 写"格式错误"四个字对模型毫无帮助。
 *
 * <p>规则与 {@code prompts/dish-generate-system.txt} 里的字段说明一一对应，
 * 改提示词时必须同步改这里，否则会出现"提示词允许但校验拒绝"的诡异现象。
 */
@Component
public class DishDraftValidator {

    /** 主料用量上限：一人份 150~250g，6 人份也就 1500g，800 是留给单人多份的余量 */
    private static final int MAX_MAIN_AMOUNT_GRAM = 800;

    /** 调料用量上限 —— 实测出现过"料酒 300g"这种夸张值 */
    private static final int MAX_SEASONING_GRAM = 50;

    /** 明确不该出现在 ingredients 里的东西（提示词已要求不列调料） */
    private static final List<String> SEASONING_KEYS =
            List.of("盐", "糖", "酱油", "生抽", "老抽", "料酒", "醋", "蚝油");

    /**
     * 校验草稿。
     *
     * @param draft               待校验的草稿
     * @param requiredIngredients 用户提供的食材名（这些必须原样出现在结果里）
     * @return 问题列表；空表示通过
     */
    public List<String> validate(DishDraft draft, Collection<String> requiredIngredients) {
        List<String> problems = new ArrayList<>();
        if (draft == null) {
            problems.add("输出为空，没有解析出任何字段");
            return problems;
        }

        checkText(problems, "name", draft.getName(), 1, 12);
        checkText(problems, "summary", draft.getSummary(), 20, 40);
        checkRange(problems, "prepTimeMin", draft.getPrepTimeMin(), 2, 60);
        checkRange(problems, "cookTimeMin", draft.getCookTimeMin(), 5, 120);
        checkRange(problems, "difficulty", draft.getDifficulty(), 1, 3);
        checkRange(problems, "spicyLevel", draft.getSpicyLevel(), 0, 3);

        checkListSize(problems, "steps", draft.getSteps(), 4, 8);
        if (draft.getSteps() != null) {
            for (int i = 0; i < draft.getSteps().size(); i++) {
                checkText(problems, "steps[" + (i + 1) + "]", draft.getSteps().get(i), 15, 50);
            }
        }
        checkListSize(problems, "seasonings", draft.getSeasonings(), 1, 4);
        checkListSize(problems, "equipment", draft.getEquipment(), 1, 3);
        checkListSize(problems, "chefTips", draft.getChefTips(), 2, 3);
        checkListSize(problems, "dietTags", draft.getDietTags(), 1, 3);

        checkIngredients(problems, draft.getIngredients());
        checkCoverage(problems, draft, requiredIngredients);
        checkNutrition(problems, draft.getNutritionPerServing());

        return problems;
    }

    // ------------------------------------------------------------------ 分组校验

    private void checkIngredients(List<String> problems, List<DishDraft.DraftIngredient> list) {
        if (list == null || list.isEmpty()) {
            problems.add("ingredients 缺失或为空");
            return;
        }
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
            } else if (gram > limitOf(name)) {
                problems.add(where + "(" + name + ") 用量 " + gram + "g 明显过大，应不超过 "
                        + limitOf(name) + "g");
            }
        }
    }

    /** 按食材名给一个"合理用量上限" */
    private int limitOf(String name) {
        if (name.endsWith("油")) {
            return MAX_SEASONING_GRAM;
        }
        for (String key : SEASONING_KEYS) {
            if (name.contains(key)) {
                return MAX_SEASONING_GRAM;
            }
        }
        return MAX_MAIN_AMOUNT_GRAM;
    }

    /**
     * 用户给的食材一个都不能少 —— 这是这个功能的根本承诺：
     * "用我手边这些东西做一道菜"，少了哪样都说不过去。
     *
     * <p>调料可以出现在 seasonings 里，也算命中（用户可能选了盐/生抽这类）。
     */
    private void checkCoverage(List<String> problems, DishDraft draft, Collection<String> required) {
        if (required == null || required.isEmpty()) {
            return;
        }
        StringBuilder pool = new StringBuilder();
        if (draft.getIngredients() != null) {
            for (DishDraft.DraftIngredient i : draft.getIngredients()) {
                if (i != null && i.getName() != null) {
                    pool.append(i.getName()).append(' ');
                }
            }
        }
        if (draft.getSeasonings() != null) {
            for (String s : draft.getSeasonings()) {
                pool.append(s).append(' ');
            }
        }
        String text = pool.toString();

        List<String> missing = new ArrayList<>();
        for (String r : required) {
            if (r != null && !r.isBlank() && !text.contains(r.trim())) {
                missing.add(r.trim());
            }
        }
        if (!missing.isEmpty()) {
            problems.add("没有用上用户提供的食材：" + String.join("、", missing));
        }
    }

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
}
