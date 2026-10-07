package com.apesource.dishservice.discover;

/**
 * 用户在发现页上表达的硬性条件。
 *
 * <p><b>这些是过滤，不是排序偏好。</b>用户勾了"只要 20 分钟内"，
 * 一道 60 分钟的菜不该因为质量高就被排进来——哪怕它确实更好吃。
 * 把它做成打分项是最容易犯的错：分数再低也还是会出现在列表里，
 * 用户会觉得"我明明选了 20 分钟，怎么还有这个"。
 *
 * <p><b>为什么没有辣度：</b>{@code DishDoc} 里没有 {@code spicyLevel} 字段，
 * ES 侧筛不了。补这个字段要改 mapping 并重建索引，收益（库里只有 21 道非辣菜）
 * 不抵成本，先不做。真要加的话三步：DishDoc 加字段 → dish-index.json 加 mapping → reindex。
 */
public record DiscoverFilter(Integer maxCookTimeMin, Integer maxDifficulty, Integer serving) {

    public static final DiscoverFilter EMPTY = new DiscoverFilter(null, null, null);

    /** 一个条件都没勾 */
    public boolean isEmpty() {
        return maxCookTimeMin == null && maxDifficulty == null && serving == null;
    }

    /**
     * 时长上限。
     *
     * <p>比较用 {@code prepTimeMin + cookTimeMin} 而不是只看 {@code cookTimeMin} 吗？
     * 不——用户说的"20 分钟能上桌"指的就是下锅到出锅，备料可以并行做别的。
     * 而且 ES 文档里只有 {@code cookTimeMin}，没有 prepTime。
     */
    public boolean rejects(Integer cookTimeMin) {
        return maxCookTimeMin != null && cookTimeMin != null && cookTimeMin > maxCookTimeMin;
    }

    public boolean tooHard(Integer difficulty) {
        return maxDifficulty != null && difficulty != null && difficulty > maxDifficulty;
    }

    /**
     * 份量是否合适。
     *
     * <p>差 1 人份算合适：库里 112 道菜有 75 道是 2 人份，
     * 卡死等值会让"3 人吃"的选择面瞬间塌掉。
     */
    public boolean wrongServing(Integer baseServing) {
        if (serving == null || baseServing == null) {
            return false;
        }
        return Math.abs(baseServing - serving) > 1;
    }
}
