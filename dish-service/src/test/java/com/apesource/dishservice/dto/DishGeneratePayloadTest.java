package com.apesource.dishservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成任务请求快照的序列化契约。
 *
 * <p>它守着的是一条<b>容易被忽略的向后兼容</b>：
 * 约束字段是后加的，而 {@code t_ai_generate_task.request_json} 里存着历史任务。
 * 如果哪天有人把 {@code constraints} 改成必填、或者读的时候忘了判空，
 * 表现就是"老的排队任务一执行就失败"，而错误信息只会写一句"落库失败"。
 */
class DishGeneratePayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("老快照没有 constraints 字段：读出来是 null，但 constraintsOrNone() 兜成空约束")
    void oldSnapshotWithoutConstraints() throws Exception {
        String oldJson = "{\"ingredients\":[{\"id\":101,\"name\":\"番茄\"}],"
                + "\"tastes\":[\"家常\"],\"servings\":2}";

        DishGeneratePayload payload = objectMapper.readValue(oldJson, DishGeneratePayload.class);

        assertThat(payload.ingredients()).hasSize(1);
        assertThat(payload.constraints()).isNull();
        assertThat(payload.constraintsOrNone()).isNotNull();
        assertThat(payload.constraintsOrNone().noConstraint()).isTrue();
    }

    @Test
    @DisplayName("带约束的快照能原样往返：存进去和读出来是同一份约束")
    void roundTripWithConstraints() throws Exception {
        GenerateConstraints constraints = new GenerateConstraints(
                List.of(101L), List.of("松露"),
                List.of(104L), List.of("香菜"),
                30, List.of("炒锅"));
        DishGeneratePayload payload = new DishGeneratePayload(
                List.of(new IngredientRef(101L, "番茄")), List.of("家常"), 2, constraints);

        String json = objectMapper.writeValueAsString(payload);
        DishGeneratePayload back = objectMapper.readValue(json, DishGeneratePayload.class);
        GenerateConstraints c = back.constraintsOrNone();

        assertThat(c.mustUseIdSet()).containsExactly(101L);
        assertThat(c.mustUseNames()).containsExactly("松露");
        assertThat(c.excludeIdSet()).containsExactly(104L);
        assertThat(c.excludeNames()).containsExactly("香菜");
        assertThat(c.maxCookTimeMin()).isEqualTo(30);
        assertThat(c.equipmentNames()).containsExactly("炒锅");
        assertThat(c.noConstraint()).isFalse();
    }

    @Test
    @DisplayName("全空约束视为「没提约束」：isBlank 为真，提示词里不会多出一段废话")
    void blankConstraints() {
        assertThat(GenerateConstraints.none().noConstraint()).isTrue();
        assertThat(new GenerateConstraints(List.of(), null, null, List.of("  "), null, null).noConstraint())
                .isTrue();
    }
}
