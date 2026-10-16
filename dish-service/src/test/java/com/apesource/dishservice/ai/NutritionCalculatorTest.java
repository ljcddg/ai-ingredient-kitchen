package com.apesource.dishservice.ai;

import com.apesource.dishservice.service.IngredientDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 营养计算客户端测试（P2-2）。
 *
 * <p>算式本身在 Python 侧，由 ai-service 的 {@code test_nutrition.py} 覆盖。
 * 这里只测 Java 侧的两件事：
 * <ol>
 *   <li><b>响应要正确映射</b>：ai-service 返回的结果能还原成 {@link NutritionCalculator.Result}，
 *       覆盖率与可信判定要和 Python 口径一致；</li>
 *   <li><b>服务不可用时不能硬算</b>：ai-service 挂了或返回错误，必须判定为"不可信"，
 *       让上层退回 AI 估算并标注来源，而不是拿残缺数据凑一个数，也不能打断落库。</li>
 * </ol>
 */
class NutritionCalculatorTest {

    private static final String URL = "http://ai-service.test";

    private IngredientDirectory directory;
    private MockRestServiceServer server;
    private NutritionCalculator calculator;

    @BeforeEach
    void setUp() {
        directory = mock(IngredientDirectory.class);
        when(directory.snapshot()).thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        server = MockRestServiceServer.bindTo(builder).build();
        calculator = new NutritionCalculator(builder.build(), directory);
    }

    @Test
    @DisplayName("响应映射：覆盖率达标 → 可信，字段与 Python 返回一一对应")
    void mapsResponseAndTrustsHighCoverage() {
        server.expect(requestTo(URL + "/api/v1/nutrition"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"ok": true, "results": [
                          {"dishId": "single", "trustable": true, "calories": 116.4,
                           "proteinGram": 4.2, "fatGram": 1.1, "carbGram": 5.0,
                           "fiberGram": 1.5, "sodiumMg": 116.4, "coverage": 1.0,
                           "resolvedItems": 2, "totalItems": 2, "unresolved": []}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300)),
                new NutritionCalculator.Item("鸡蛋", BigDecimal.valueOf(120))), 2);

        assertThat(r.calories()).isEqualTo(116.4);
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.resolvedItems()).isEqualTo(2);
        assertThat(r.trustable()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("覆盖率不足：未对上的食材列出来，判为不可信")
    void lowCoverageIsNotTrustable() {
        server.expect(requestTo(URL + "/api/v1/nutrition"))
                .andRespond(withSuccess("""
                        {"ok": true, "results": [
                          {"dishId": "single", "calories": 20, "proteinGram": 0, "fatGram": 0,
                           "carbGram": 0, "fiberGram": 0, "sodiumMg": 0, "coverage": 0.5,
                           "resolvedItems": 1, "totalItems": 2, "unresolved": ["香茅"]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(200)),
                new NutritionCalculator.Item("香茅", BigDecimal.valueOf(200))), 2);

        assertThat(r.coverage()).isEqualTo(0.5);
        assertThat(r.unresolved()).containsExactly("香茅");
        assertThat(r.trustable()).isFalse();
    }

    @Test
    @DisplayName("ai-service 返回 500：判为不可信，不抛异常打断调用方")
    void serverErrorIsNotTrustable() {
        server.expect(requestTo(URL + "/api/v1/nutrition"))
                .andRespond(withServerError());

        NutritionCalculator.Result r = calculator.calculate(List.of(
                new NutritionCalculator.Item("番茄", BigDecimal.valueOf(300))), 2);

        assertThat(r.trustable()).isFalse();
        assertThat(r.calories()).isZero();
        assertThat(r.unresolved()).containsExactly("番茄");
    }

    @Test
    @DisplayName("没有可算的食材：不调用 ai-service，直接返回不可信")
    void emptyInputSkipsRemoteCall() {
        assertThat(calculator.calculate(List.of(), 2).trustable()).isFalse();
        assertThat(calculator.calculate(null, 2).trustable()).isFalse();
        server.verify();
    }
}
