package com.apesource.dishservice.ai;

import com.apesource.dishservice.dto.DishDraft;
import com.apesource.dishservice.dto.GenerateConstraints;
import com.apesource.dishservice.dto.IngredientRef;
import com.apesource.dishservice.mapper.IngredientMapper;
import com.apesource.dishservice.service.IngredientDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型调用超时的行为测试。
 *
 * <p>为什么这条值得单测：超时逻辑最容易写错的方式是"看起来有超时、其实没生效"——
 * 比如把超时加在了错误的位置，或者异常被外层 catch 吞掉换成了一句笼统的失败信息。
 * 所以这里一边断言<b>异常信息里能看出是超时</b>，一边断言<b>真的没被拖住</b>（用耗时）。
 */
class AiDishGeneratorTest {

    private OllamaChatModel chatModel;
    private DishDraftValidator validator;
    private GenerateProperties properties;
    private IngredientMapper ingredientMapper;
    private ReferenceDishRetriever referenceRetriever;
    private AiDishGenerator generator;

    @BeforeEach
    void setUp() {
        chatModel = mock(OllamaChatModel.class);
        validator = mock(DishDraftValidator.class);
        properties = new GenerateProperties();
        ingredientMapper = mock(IngredientMapper.class);
        referenceRetriever = mock(ReferenceDishRetriever.class);

        generator = new AiDishGenerator(chatModel, validator, properties, new ObjectMapper(),
                ingredientMapper, referenceRetriever, mock(IngredientDirectory.class));
        ReflectionTestUtils.setField(generator, "model", "test-model");
        generator.loadSystemPrompt();

        when(referenceRetriever.retrieve(any(), anyInt())).thenReturn(List.of());
        when(referenceRetriever.render(any())).thenReturn("");
    }

    private DishGenerator.GenerateInput input() {
        return new DishGenerator.GenerateInput(
                List.of(new IngredientRef(101L, "番茄"), new IngredientRef(102L, "鸡蛋")),
                List.of("家常"), 2, GenerateConstraints.none());
    }

    private ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("模型超时：抛出的信息里能看出是超时，而且不等它跑完")
    void timesOutInsteadOfWaitingForever() {
        properties.setAttemptTimeoutSeconds(1);
        // 模型"卡住"4 秒，超时设 1 秒
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(4000);
            return reply("{}");
        });

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(DishGenerationException.class)
                .hasMessageContaining("超时");
        long cost = System.currentTimeMillis() - start;

        assertThat(cost)
                .as("应当在超时时间附近就返回，而不是等模型自己跑完")
                .isLessThan(3500L);
    }

    @Test
    @DisplayName("模型正常返回：包一层超时不影响成功路径")
    void normalCallStillWorks() {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("{\"name\":\"番茄炒蛋\"}"));
        when(validator.validateDetailed(any(DishDraft.class), any()))
                .thenReturn(new DishDraftValidator.ValidationResult(List.of(), List.of()));

        DishGenerator.GenerateResult result = generator.generate(input());

        assertThat(result.draft().getName()).isEqualTo("番茄炒蛋");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.rawOutput()).contains("番茄炒蛋");
    }

    @Test
    @DisplayName("模型调用抛异常：转成带原因的生成失败（保留原始错误信息）")
    void modelFailureIsWrapped() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Connection refused: localhost:11434"));

        assertThatThrownBy(() -> generator.generate(input()))
                .isInstanceOf(DishGenerationException.class)
                .hasMessageContaining("调用模型失败")
                .hasMessageContaining("Connection refused");
    }
}
