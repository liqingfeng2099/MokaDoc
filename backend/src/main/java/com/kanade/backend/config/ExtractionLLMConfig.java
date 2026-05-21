package com.kanade.backend.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图谱抽取专用 LLM 配置。
 * 与会话 LLM 分离，可独立指定模型和参数（低温度、高确定性），
 * 使用非流式 ChatModel（抽取需要完整 JSON 解析）。
 *
 * @author kanade
 */
@Configuration
public class ExtractionLLMConfig {

    @Value("${graph.extraction.llm.base-url}")
    private String baseUrl;

    @Value("${graph.extraction.llm.api-key}")
    private String apiKey;

    @Value("${graph.extraction.llm.model-name}")
    private String modelName;

    @Value("${graph.extraction.llm.max-tokens:4096}")
    private int maxTokens;

    @Value("${graph.extraction.llm.temperature:0.1}")
    private double temperature;

    @Bean(name = "extractionChatModel")
    public ChatModel extractionChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
    }
}
