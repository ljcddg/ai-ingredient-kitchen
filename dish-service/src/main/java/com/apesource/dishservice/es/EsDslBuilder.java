package com.apesource.dishservice.es;

import com.apesource.dishservice.config.EsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES 召回 DSL 组装器。
 *
 * <p>把「用户选了哪几样食材」翻译成 ES 的查询 JSON。这个类是整个召回链路里
 * 最容易出错的地方，几个关键点必须说清楚：
 *
 * <h3>一、{@code terms} 查询默认是 OR，不是 AND</h3>
 * <pre>
 * // 错误理解：以为这是「必须含 101 和 102 和 103」
 * // 实际语义：含 101 或 102 或 103 任意一个 —— 纯粹的 OR 召回
 * { "terms": { "ingredientIds": [101, 102, 103] } }
 * </pre>
 * 要表达「必须全部包含」，只能为每种食材拼一个独立的 {@code term}，全部放进 {@code must}。
 * 这是本类存在的主要理由 —— 把这个坑挡在一个地方，别散落到业务代码里。
 *
 * <h3>二、食材有两条路：ID 和名字</h3>
 * 在食材表里点选的食材有主数据 ID，查 {@code ingredientIds}；
 * 在输入框里自由敲的食材（如「松露」）没有 ID，只能查 {@code ingredientNames}。
 * 两条路都是「一样食材一个独立子句」，于是「命中几个子句」就等于「覆盖了几样食材」——
 * 覆盖率因此可以直接由命中数得出，不必再单独算集合交集。
 * <p>之所以不用一个 {@code terms} 数组，是因为 {@code terms} 一次只能查一个字段，
 * 而我们要混合两种字段。
 *
 * <h3>三、status / deleted 必须放 filter</h3>
 * {@code filter} 不参与打分、还能走查询缓存。
 * 更要紧的是：漏掉 {@code status = 1} 会把草稿（还没发布的菜）召回给用户，
 * 这是数据泄漏级别的问题。
 */
@Component
@RequiredArgsConstructor
public class EsDslBuilder {

    /** 召回时只取这些字段，减少网络传输 */
    private static final List<String> SOURCE_FIELDS = List.of(
            "dishId", "name", "nameKey", "status", "deleted", "source", "creatorId",
            "ingredientIds", "mainIngredientIds", "ingredientNames", "ingredientCount",
            "tasteTags", "tagIds", "baseServing", "difficulty", "cookTimeMin",
            "ratingAvg", "ratingWilson", "ratingCount", "collectCount", "viewCount",
            "energyKcal", "publishTime"
    );

    /** 相似召回的放宽比例：至少命中所选食材的 60% */
    private static final double PARTIAL_MIN_RATIO = 0.6;

    private final EsProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 组装召回查询体。
     *
     * @param ingredientIds 用户选中的食材 ID（调用前已去重排序），可空
     * @param freeNames     用户自由输入的食材名（食材表之外、没有主数据 ID），可空
     * @param tasteTags     口味标签，可空
     * @param allowPartial  是否允许缺食材（软匹配）
     * @return ES {@code _search} 的请求体 JSON
     */
    public String buildRecallBody(List<Long> ingredientIds, List<String> freeNames,
                                  List<String> tasteTags, boolean allowPartial) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode functionScore = root.putObject("query").putObject("function_score");
        ObjectNode bool = functionScore.putObject("query").putObject("bool");

        // ---------------- filter：纯过滤条件 ----------------
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("term").put("status", 1);
        filters.addObject().putObject("term").put("deleted", 0);
        if (tasteTags != null && !tasteTags.isEmpty()) {
            ArrayNode tagArray = filters.addObject().putObject("terms").putArray("tasteTags");
            tasteTags.forEach(tagArray::add);
        }

        // ---------------- 食材约束 ----------------
        buildIngredientClause(bool, ingredientIds, freeNames, allowPartial);

        // ---------------- function_score：粗排加权 ----------------
        ArrayNode functions = functionScore.putArray("functions");

        // 1) 威尔逊下界：权重最高，抗「1 个人打 5 分」这类小样本干扰
        functions.addObject().putObject("field_value_factor")
                .put("field", "ratingWilson")
                .put("factor", 10)
                .put("missing", 0);

        // 2) 收藏热度：log1p 压平 —— 1000 收藏不该是 100 收藏的 10 倍权重
        functions.addObject().putObject("field_value_factor")
                .put("field", "collectCount")
                .put("factor", 0.5)
                .put("modifier", "log1p")
                .put("missing", 0);

        // 3) 新鲜度：新发布的菜小幅加成，老菜衰减但不归零
        functions.addObject().putObject("gauss").putObject("publishTime")
                .put("origin", "now")
                .put("scale", "30d")
                .put("decay", 0.5);

        functionScore.put("score_mode", "sum");
        functionScore.put("boost_mode", "sum");

        // 召回池大小（应用层会在这个池子里按覆盖率精排 + 分页）
        root.put("size", properties.getRecall().getTopN());

        ArrayNode source = root.putArray("_source");
        SOURCE_FIELDS.forEach(source::add);

        return toJson(root);
    }

    /**
     * 组装食材约束条件 —— 本类的核心。
     *
     * <p><b>为什么逐项拼子句，而不是用一个 {@code terms} 数组：</b>
     * <ol>
     *   <li>食材有 ID（表内）和名字（表外）两个来源，而 {@code terms} 一次只能查一个字段；</li>
     *   <li>逐项子句之后，「命中几个子句」就等于「覆盖了几样食材」，
     *       软匹配的 {@code minimum_should_match} 因此能直接表达为「至少覆盖几样」。</li>
     * </ol>
     *
     * <p>硬约束模式：全部子句放进 {@code must}，一样都不能少。
     * <p>软匹配模式：全部子句放进 {@code should}，{@code minimum_should_match} 取
     * 「食材总数 × 60%」向上取整。
     */
    private void buildIngredientClause(ObjectNode bool, List<Long> ingredientIds,
                                       List<String> freeNames, boolean allowPartial) {
        List<Long> ids = ingredientIds == null ? List.of() : ingredientIds;
        List<String> names = freeNames == null ? List.of() : freeNames;
        int total = ids.size() + names.size();

        if (allowPartial) {
            int minMatch = Math.max(1, (int) Math.ceil(total * PARTIAL_MIN_RATIO));

            ArrayNode should = bool.putArray("should");
            ids.forEach(id -> should.addObject().putObject("term").put("ingredientIds", id));
            names.forEach(name -> should.addObject()
                    .putObject("term").put("ingredientNames", name));

            // 【踩过的坑】minimum_should_match 必须设在 bool 上，不能塞进 terms 里：
            // terms 查询不认这个参数，塞进去会被 ES 当成一个"待匹配的字段名"，
            // 那条分支于是永远不命中 —— 软匹配会悄悄退化成「至少中一样」。
            bool.put("minimum_should_match", minMatch);
            return;
        }

        ArrayNode must = bool.putArray("must");
        ids.forEach(id -> must.addObject().putObject("term").put("ingredientIds", id));
        names.forEach(name -> must.addObject().putObject("term").put("ingredientNames", name));
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("组装 ES 召回 DSL 失败", e);
        }
    }
}
