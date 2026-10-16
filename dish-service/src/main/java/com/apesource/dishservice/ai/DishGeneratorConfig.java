package com.apesource.dishservice.ai;

import com.apesource.dishservice.mapper.IngredientMapper;
import com.apesource.dishservice.service.IngredientDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生成器二选一：本地（Spring AI + Ollama）或远程（Python ai-service）。
 *
 * <p>用配置开关而不是代码开关：对照实验（同一批查询跑本地/远程两臂）、
 * 出事回滚（远程有问题切回 local）都不需要重新打包。
 * {@code ai.generate.mode} 缺省按 local 处理 —— 没配这项时行为与迁移前逐字节一致。
 *
 * <p>两个实现都<b>不能</b>加 {@code @Component}：{@link DishGenerator} 只有一个
 * 注入点（{@code DishGenerationExecutor}），两个 bean 会按类型注入歧义。
 */
@Configuration
public class DishGeneratorConfig {

    @Bean
    @ConditionalOnProperty(name = "ai.generate.mode", havingValue = "local", matchIfMissing = true)
    public DishGenerator localDishGenerator(OllamaChatModel chatModel,
                                            DishDraftValidator validator,
                                            GenerateProperties properties,
                                            ObjectMapper objectMapper,
                                            IngredientMapper ingredientMapper,
                                            ReferenceDishRetriever referenceRetriever,
                                            IngredientDirectory directory) {
        return new AiDishGenerator(chatModel, validator, properties, objectMapper,
                ingredientMapper, referenceRetriever, directory);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.generate.mode", havingValue = "remote")
    public DishGenerator remoteDishGenerator(GenerateProperties properties,
                                             ReferenceDishRetriever referenceRetriever,
                                             IngredientDirectory directory,
                                             ObjectMapper objectMapper,
                                             RemoteProperties remoteProperties) {
        return new RemoteDishGenerator(properties, referenceRetriever, directory,
                objectMapper, remoteProperties);
    }
}
