package com.apesource.dishservice.ai;

import com.apesource.commonservice.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI 兼容性探针。
 *
 * <p>存在的唯一目的：回答「Spring Boot 3.2.4 到底能不能直接跑 Spring AI」这个问题。
 * 官方要求 Spring AI 跑在 Boot 3.4.x / 3.5.x 上，本项目是 3.2.4，属于非支持组合，
 * 所以用实测而不是推测来下结论。实验完成后这个类可以删掉。
 *
 * <p>为什么用 {@link ObjectProvider} 而不是直接注入 {@code OllamaChatModel}：
 * 如果自动配置没生效，直接注入会让整个服务启动失败，那样就只能看到一行
 * "No qualifying bean"，拿不到「bean 到底有没有被创建」这个关键信息。
 * 用 ObjectProvider 让服务先起来，再由接口告诉我们真实情况。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiProbeController {

    private final ObjectProvider<OllamaChatModel> chatModelProvider;

    /**
     * 探针接口。返回四段信息：Boot 版本、bean 状态、真实调用结果、出错时的根因。
     *
     * <p>三种可能的结论：
     * <ul>
     *   <li>bean 缺失 —— 自动配置没生效（版本不兼容的典型表现）</li>
     *   <li>探测抛异常 —— 通常是 BeanCreationException / NoClassDefFoundError，
     *       说明自动配置能被扫到，但创建 bean 时用到了本项目没有的 API</li>
     *   <li>调用成功 —— 真的能用</li>
     * </ul>
     */
    @GetMapping("/probe")
    public Result<Map<String, Object>> probe(
            // 显式写 name：即便父 pom 的 -parameters 哪天被谁删了，
            // 这里也不会退化成「反射拿不到参数名」的 500。
            @RequestParam(name = "ingredients", defaultValue = "番茄、鸡蛋、葱") String ingredients) {

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("bootVersion", SpringBootVersion.getVersion());
        info.put("springAiModel", "gemma4:e2b");

        // 整个探测过程都包在 try 里，包括「取 bean」这一步。
        // 第一版只把 model.call() 包了起来，结果接口返回一个被全局异常处理器
        // 兜住的 500，什么信息都没拿到 —— 实测下来失败点恰恰就在取 bean 这里。
        long start = System.currentTimeMillis();
        try {
            OllamaChatModel model = chatModelProvider.getIfAvailable();
            if (model == null) {
                info.put("chatModelBean", "缺失");
                info.put("conclusion", "自动配置未生效：OllamaChatModel 没有被创建");
                return Result.success(info);
            }
            info.put("chatModelBean", model.getClass().getName());

            String prompt = "食材：" + ingredients + "。请用一句中文说出可以做什么菜，不要解释。";
            info.put("prompt", prompt);

            String answer = model.call(prompt);
            info.put("costMillis", System.currentTimeMillis() - start);
            info.put("answer", answer);
            info.put("conclusion", "调用成功");
        } catch (Throwable e) {
            // 捕 Throwable：NoSuchMethodError / NoClassDefFoundError 这类
            // 「版本不兼容」的典型症状都是 Error 而不是 Exception。
            info.put("costMillis", System.currentTimeMillis() - start);
            info.put("errorType", e.getClass().getName());
            info.put("errorMessage", String.valueOf(e.getMessage()));

            // 根因通常埋在 cause 链最里层，取出来能省掉翻日志的功夫
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            info.put("rootCauseType", root.getClass().getName());
            info.put("rootCauseMessage", String.valueOf(root.getMessage()));
            info.put("conclusion", "探测过程抛异常，见 errorType / rootCause");
        }
        return Result.success(info);
    }
}
