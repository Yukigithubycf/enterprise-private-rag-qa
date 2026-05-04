package com.yuki.enterprise_private_rag_qa.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * RAG Pipeline LLM 客户端，支持同步非流式调用。
 * 用于 Entity Extraction、Query Rewrite、HyDE Generation、Reflection 等节点。
 */
@Component
public class RagLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(RagLlmClient.class);

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public RagLlmClient(
            @Value("${deepseek.api.url}") String apiUrl,
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.model}") String model,
            ObjectMapper objectMapper) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步 LLM 调用，返回纯文本响应。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param temperature  采样温度
     * @return 模型返回的文本
     */
    public String chatSync(String systemPrompt, String userPrompt, double temperature) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "stream", false,
                    "temperature", temperature
            );

            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseContent(response);
        } catch (Exception e) {
            logger.error("RAG LLM 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("RAG LLM 调用失败", e);
        }
    }

    /**
     * 解析 chat/completions 响应，提取 content 字段。
     */
    private String parseContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            logger.error("解析 LLM 响应失败: {}", e.getMessage(), e);
            return "";
        }
    }
}
