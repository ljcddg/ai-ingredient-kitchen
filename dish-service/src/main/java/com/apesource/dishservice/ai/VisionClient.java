package com.apesource.dishservice.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * YOLO 识别客户端 —— 调 Python ai-service 的 {@code POST /api/v1/vision/dishes}。
 *
 * <p>与 {@link RemoteDishGenerator} 同一模式：只认 HTTP 契约，不关心 ai-service
 * 背后是 COCO 预训练权重还是自训 best.pt，换模型 = 换 Python 侧权重文件，这里零改动。
 *
 * <p>职责边界（docs/yolo-vision-design.md §5.3）：Python 只回答"图里有什么"
 * （label + box + confidence），label → 食材 ID 的映射在 Java 侧做。
 */
@Slf4j
public class VisionClient {

    private final VisionProperties properties;
    private final RestClient restClient;

    public VisionClient(VisionProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 识别是秒级操作（GPU 单图 4.4ms），60s 是宽上限，不像生成那样需要三层超时链
        factory.setReadTimeout(Duration.ofSeconds(
                Math.max(1, properties.getReadTimeoutSeconds())));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.getBaseUrl())
                .build();
        log.info("识别客户端已启用：ai-service={}，读超时={}s",
                properties.getBaseUrl(), properties.getReadTimeoutSeconds());
    }

    /** 新建 requestId（识别链路的对账键：Python 响应、日志、t_vision_detect_log 用同一个） */
    public String newRequestId() {
        return "vision-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 批量识别。
     *
     * @param images [{"imageId": "...", "mediaPath": "..."}]
     * @return Python 响应；{@code ok=false} 时调用方按 {@code errorKind} 分类处理
     */
    public PythonVisionResponse detect(List<java.util.Map<String, String>> images, String requestId) {
        return restClient.post()
                .uri("/api/v1/vision/dishes")
                .body(java.util.Map.of("requestId", requestId, "images", images))
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return response.bodyTo(PythonVisionResponse.class);
                    }
                    // 4xx/5xx 也带 body（Python 侧错误分类），尽力解析出来；
                    // 解析失败就返回一个通用 internal，不让 RestClient 抛默认异常把错误信息糊掉
                    try {
                        PythonVisionResponse parsed =
                                response.bodyTo(PythonVisionResponse.class);
                        if (parsed != null) {
                            return parsed;
                        }
                    } catch (Exception ignored) {
                        // 落到底层 fallback
                    }
                    return PythonVisionResponse.failed("internal",
                            "ai-service 返回 " + response.getStatusCode().value());
                });
    }

    // ---------- Python 侧响应契约（docs/yolo-vision-design.md §5.3） ----------

    /** 忽略未知字段：Python 加字段时不至于让 Java 侧解析失败 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonVisionResponse(
            boolean ok,
            String errorKind,
            String error,
            Integer costMs,
            String model,
            String device,
            List<PythonImage> images) {

        static PythonVisionResponse failed(String kind, String message) {
            return new PythonVisionResponse(false, kind, message, 0, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonImage(
            String imageId,
            List<PythonDetection> detections,
            String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonDetection(
            String label,
            Double confidence,
            List<Integer> box) {
    }
}
