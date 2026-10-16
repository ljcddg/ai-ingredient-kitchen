package com.apesource.dishservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 生成结果的结构映射 —— 字段名必须与 {@code ai-service/prompts/dish-generate-system.txt}
 * 里定义的 JSON 一字不差，否则反序列化会静默变成 null。
 *
 * <p>用包装类型（Integer 而不是 int）：这样"字段缺失"能被识别出来，
 * 校验环节才能给出"缺少 difficulty"这种明确的错误，而不是拿到一个 0 分不清真假。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DishDraft {

    private String name;
    private String summary;
    private Integer prepTimeMin;
    private Integer cookTimeMin;
    private Integer difficulty;
    private Integer spicyLevel;
    private List<String> steps;
    private List<DraftIngredient> ingredients;
    private List<String> seasonings;
    private List<String> equipment;
    private NutritionDraft nutritionPerServing;
    private List<String> dietTags;
    private List<String> substitutes;
    private List<String> chefTips;

    /** 主料 / 辅料（不含调料 —— 调料走 seasonings） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DraftIngredient {
        private String name;
        private Integer amountGram;
    }

    /** 每人份营养估算值 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NutritionDraft {
        private Integer calories;
        private Integer proteinGram;
        private Integer fatGram;
        private Integer carbGram;
        private Integer fiberGram;
        private Integer sodiumMg;
    }
}
