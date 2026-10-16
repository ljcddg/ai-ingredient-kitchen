package com.apesource.dishservice.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 远程 AI 服务（Python ai-service）的连接配置。
 *
 * <p>ai-service 是 Flask 应用，<b>不走 Nacos 注册</b>（无状态计算服务不需要
 * 服务发现），地址由配置中心下发。所以这里全是"连哪、怎么连"的事。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.remote")
public class RemoteProperties {

    /** ai-service 地址（本机默认） */
    private String baseUrl = "http://127.0.0.1:8090";

    /**
     * 生成用的模型名，随请求透传给 ai-service。
     *
     * <p>换模型只改 Nacos 的这一项，不用动代码。ai-service 自己 config.yaml 里的默认值只在请求没带模型时生效。
     */
    private String model = "gemma4:e2b";

    /**
     * 服务间共享令牌（防误调）。为空表示不校验 —— ai-service 只监听内网、
     * 不进 gateway，威胁模型有限；要对外暴露前必须配上。
     */
    private String token = "";

    /**
     * Java 侧读超时（秒）。
     *
     * <p>必须大于 Python 内部两层超时（Ollama 调用 600s &lt; Flask worker 630s），
     * 留 30s 给序列化和网络余量。改超时必须三层一起改，否则 Java 先断、
     * Python 还在跑，错误信息全部错位。
     */
    private int readTimeoutSeconds = 660;
}
