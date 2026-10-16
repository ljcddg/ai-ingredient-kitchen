package com.apesource.dishservice.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 识菜链路配置（{@code ai.vision.*}）。
 *
 * <p>与 {@link RemoteProperties} 分开配：识别与生成的超时链不同
 * （识别是秒级操作，60s 足够；生成要 660s），合并会让两个场景互相将就。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.vision")
public class VisionProperties {

    /** ai-service 地址（与 ai.remote.base-url 同值，Nacos 里一起配） */
    private String baseUrl = "http://127.0.0.1:8090";

    /** 服务间共享令牌（与 ai.remote.token 一致） */
    private String token = "";

    /** Java 侧读超时（秒）。9 张图 CPU 模式秒级，60s 是宽上限 */
    private int readTimeoutSeconds = 60;

    /** 单次请求最多几张图（Python 侧 vision.max_images 同步改，两边一起动） */
    private int maxImages = 9;

    /** 单张图片大小上限（MB），超限直接拒 */
    private int maxFileSizeMb = 10;

    /** 上传根目录（相对 dish-service 工作目录）。目录+文件名保持纯 ASCII：
     *  cv2.imread 不吃中文路径且失败静默返 None */
    private String uploadDir = "uploads/vision";

    /** 高置信默认勾选线：maxConfidence ≥ 此值的候选默认勾选 */
    private double defaultCheckedConfidence = 0.8;

    /** 裁剪小图子目录（uploadDir 下） */
    private String cropSubDir = "crops";
}
