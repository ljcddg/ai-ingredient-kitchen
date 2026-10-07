package com.apesource.dishservice.es;

import com.apesource.dishservice.config.EsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ES 召回执行器。
 *
 * <p>只做三件事：调 {@link EsDslBuilder} 拿到 DSL、发给 ES、把响应解析成 {@link RecalledDish}。
 * 打分与业务判定都不在这里 —— 那些属于 Service 层。
 *
 * <p>另外一个刻意的设计：<b>ES 不可用时不抛异常，返回空列表</b>。
 * 召回是读路径，它挂掉不该让整个接口 500。返回空之后由 Service 决定是降级到
 * MySQL 倒排（见 sql/dish.sql 末尾的参考 SQL）还是提示用户稍后重试。
 */
@Repository
@RequiredArgsConstructor
public class DishSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(DishSearchRepository.class);

    private final RestClient esRestClient;

    private final EsProperties properties;

    private final EsDslBuilder dslBuilder;

    private final ObjectMapper objectMapper;

    /**
     * 按食材组合召回候选菜品。
     *
     * @param ingredientIds 已去重排序的食材 ID（有主数据 ID 的那部分），可空
     * @param freeNames     自由输入的食材名（食材表之外、没有 ID 的那部分），可空
     * @param tasteTags     口味标签，可空
     * @return 候选列表；ES 不可用或索引不存在时返回空列表
     */
    public List<RecalledDish> recall(List<Long> ingredientIds, List<String> freeNames,
                                     List<String> tasteTags) {
        return recall(ingredientIds, freeNames, tasteTags, false);
    }

    /**
     * 按食材组合召回候选菜品。
     *
     * @param ingredientIds 已去重排序的食材 ID（有主数据 ID 的那部分），可空
     * @param freeNames     自由输入的食材名（食材表之外、没有 ID 的那部分），可空
     * @param tasteTags     口味标签，可空
     * @param includePool   是否包含推荐池的新菜（rankLevel=1）。
     *                      搜索传 false（保持原行为），发现流传 true（新菜在这里拿曝光）
     * @return 候选列表；ES 不可用或索引不存在时返回空列表
     */
    public List<RecalledDish> recall(List<Long> ingredientIds, List<String> freeNames,
                                     List<String> tasteTags, boolean includePool) {
        String body = dslBuilder.buildRecallBody(ingredientIds, freeNames, tasteTags, includePool);
        log.debug("ES 召回 DSL = {}", body);

        String responseJson;
        try {
            responseJson = esRestClient.post()
                    .uri("/{index}/_search", properties.getDishIndex())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // 索引还没建时 ES 返回 404 index_not_found_exception，
            // 这属于「还没初始化好」而不是故障，打成 warn 就够
            log.warn("ES 召回失败（HTTP {}）：{}", e.getStatusCode().value(), brief(e.getResponseBodyAsString()));
            return Collections.emptyList();
        } catch (ResourceAccessException e) {
            // 连不上 ES（服务没启动 / 端口不通）
            log.warn("无法连接 Elasticsearch [{}]：{}", properties.getUris(), e.getMessage());
            return Collections.emptyList();
        }

        return parseHits(responseJson);
    }

    /** 解析 {@code hits.hits[]}，取出 _score 与 _source */
    private List<RecalledDish> parseHits(String responseJson) {
        List<RecalledDish> result = new ArrayList<>();
        if (responseJson == null || responseJson.isBlank()) {
            return result;
        }

        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray()) {
                return result;
            }

            for (JsonNode hit : hits) {
                DishDoc doc = objectMapper.treeToValue(hit.path("_source"), DishDoc.class);
                RecalledDish item = new RecalledDish();
                item.setDoc(doc);
                item.setEsScore(hit.path("_score").asDouble(0.0d));
                result.add(item);
            }

            long total = root.path("hits").path("total").path("value").asLong(result.size());
            log.debug("ES 召回命中 {} 条，返回 {} 条", total, result.size());
        } catch (Exception e) {
            log.error("解析 ES 响应失败", e);
            return Collections.emptyList();
        }

        return result;
    }

    /**
     * 探活：ES 是否可用。
     *
     * <p>召回返回空有两种可能 ——「真的没有匹配的菜」和「ES 挂了」。
     * 业务上必须区分：前者是正常结果（去调 AI），后者要提示降级。
     * 所以单独给一个探测入口，而不是让调用方猜。
     */
    public boolean ping() {
        try {
            esRestClient.get().uri("/").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("ES 探活失败：{}", e.getMessage());
            return false;
        }
    }

    /** 截断过长的错误响应，避免日志被刷屏 */
    private String brief(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
