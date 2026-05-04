package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM 驱动的实体与关键词抽取。
 * 从用户问题中抽取用于检索的核心实体（方法名、系统名、模块名等）和关键词。
 */
@Service
public class EntityExtractor {

    private static final Logger logger = LoggerFactory.getLogger(EntityExtractor.class);
    private static final String SYSTEM_PROMPT = "你是一个 RAG 查询分析器。只输出 JSON，不输出其他内容。";

    private static final String PROMPT_TEMPLATE = """
            你是一个 RAG 查询分析器。

            请从用户问题中抽取用于检索的核心实体和关键词。

            要求：
            1. 实体包括方法名、系统名、模块名、数据集名、函数名、类名、指标名、专有名词。
            2. 关键词包括能帮助检索的中文术语、英文术语、缩写。
            3. 不要回答问题。
            4. 不要引入用户问题和对话历史中没有出现或无法合理推断的实体。
            5. 输出 JSON。

            用户问题：
            {normalized_query}

            对话历史：
            {chat_history}

            输出格式：
            {
              "entities": [],
              "keywords": []
            }""";

    private final RagLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public EntityExtractor(RagLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 抽取实体和关键词。
     *
     * @param normalizedQuery 规范化后的 query
     * @param chatHistory     对话历史（可为 null）
     * @return [entities, keywords] 两个 List
     */
    public EntityResult extract(String normalizedQuery, String chatHistory) {
        try {
            String historyText = chatHistory != null ? chatHistory : "（无对话历史）";
            String prompt = PROMPT_TEMPLATE
                    .replace("{normalized_query}", normalizedQuery)
                    .replace("{chat_history}", historyText);

            logger.debug("Entity extraction prompt for query: {}", normalizedQuery);
            String response = llmClient.chatSync(SYSTEM_PROMPT, prompt, 0.1);

            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);

            List<String> entities = new ArrayList<>();
            List<String> keywords = new ArrayList<>();

            if (root.has("entities") && root.get("entities").isArray()) {
                root.get("entities").forEach(n -> entities.add(n.asText()));
            }
            if (root.has("keywords") && root.get("keywords").isArray()) {
                root.get("keywords").forEach(n -> keywords.add(n.asText()));
            }

            logger.debug("Extracted entities: {}, keywords: {}", entities, keywords);
            return new EntityResult(entities, keywords);

        } catch (Exception e) {
            logger.error("Entity extraction failed: {}", e.getMessage(), e);
            // Fallback: 空列表
            return new EntityResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 片段。
     */
    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    /**
     * 实体抽取结果。
     */
    public record EntityResult(List<String> entities, List<String> keywords) {
    }
}
