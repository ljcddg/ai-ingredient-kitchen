package com.apesource.dishservice.es;

import com.apesource.dishservice.config.EsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 召回 DSL 组装测试。
 *
 * <p>守的是那几个"写错了也照样返回 200、只是结果莫名其妙"的地方：
 * <ul>
 *   <li>{@code terms} 默认是 OR —— 要"全部包含"必须逐项独立子句；</li>
 *   <li>表外食材得同时走 keyword 精确匹配和菜名分词匹配（⑤ 混合召回的字面档）；</li>
 *   <li>status / deleted 必须在 filter 里，漏了就是把草稿菜召回给用户。</li>
 * </ul>
 */
class EsDslBuilderTest {

    private EsDslBuilder builder;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        builder = new EsDslBuilder(new EsProperties(), mapper);
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("表内食材：一样一个独立 term，放进 should —— 才是「至少命中一样」而不是 OR 数组")
    void ingredientIdsBecomeSeparateShouldClauses() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(101L, 102L), List.of(), null));
        JsonNode should = root.at("/query/function_score/query/bool/should");

        assertThat(should).hasSize(2);
        assertThat(should.get(0).at("/term/ingredientIds").asLong()).isEqualTo(101L);
        assertThat(should.get(1).at("/term/ingredientIds").asLong()).isEqualTo(102L);
        assertThat(root.at("/query/function_score/query/bool/minimum_should_match").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("表外食材：keyword 精确 + 菜名分词两条腿，菜名那条加权 2 倍")
    void freeNamesGetLexicalFallbackOnDishName() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(), List.of("香茅"), null));
        JsonNode should = root.at("/query/function_score/query/bool/should");

        assertThat(should).hasSize(2);
        assertThat(should.get(0).at("/term/ingredientNames").asText()).isEqualTo("香茅");

        JsonNode match = should.get(1).at("/match/name");
        assertThat(match.get("query").asText()).isEqualTo("香茅");
        assertThat(match.get("boost").asDouble()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("表内食材不加菜名匹配 —— 只有 ID 时不该凭空造一个文本条件")
    void noLexicalClauseWithoutFreeNames() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(101L), null, null));
        JsonNode should = root.at("/query/function_score/query/bool/should");

        assertThat(should).hasSize(1);
        assertThat(should.get(0).has("match")).isFalse();
    }

    @Test
    @DisplayName("status / deleted 必须在 filter 里，且默认只召回已晋级（rankLevel=2）")
    void filtersDraftOutAndKeepsPoolOutByDefault() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(101L), List.of(), null));
        JsonNode filters = root.at("/query/function_score/query/bool/filter");

        assertThat(filters.toString()).contains("\"status\":1");
        assertThat(filters.toString()).contains("\"deleted\":0");
        assertThat(filters.toString()).contains("\"rankLevel\":2");
    }

    @Test
    @DisplayName("发现流（includePool）：rankLevel 放开到 1 和 2，新菜才有曝光机会")
    void includePoolAllowsNewDishes() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(101L), List.of(), null, true));
        JsonNode filters = root.at("/query/function_score/query/bool/filter");

        JsonNode levels = filters.findValue("rankLevel");
        assertThat(levels.isArray()).isTrue();
        assertThat(levels).hasSize(2);
    }

    @Test
    @DisplayName("口味标签进 filter（纯过滤，不参与打分）")
    void tasteTagsGoToFilter() throws Exception {
        JsonNode root = parse(builder.buildRecallBody(List.of(101L), List.of(), List.of("家常", "清淡")));
        JsonNode tags = root.at("/query/function_score/query/bool/filter").findValue("tasteTags");

        assertThat(tags).hasSize(2);
    }
}
