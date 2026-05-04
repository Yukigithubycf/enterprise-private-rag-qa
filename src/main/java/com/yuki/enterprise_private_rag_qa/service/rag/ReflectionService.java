package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 检索质量审查器（Reflection Node）。
 * 使用 LLM 判断当前 top-k context 是否足够支持回答用户问题。
 *
 * 这不是答案生成节点 —— 它的输出是检索控制信号，不是证据。
 */
@Component
public class ReflectionService {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionService.class);

    private final RagLlmClient llmClient;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public ReflectionService(RagLlmClient llmClient, RagProperties ragProperties, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断当前检索到的 context 是否足够支持回答用户问题（不含对话历史）。
     */
    public ReflectionResult check(String rawQuery, String intent, String mainRewrittenQuery,
                                   List<SearchResult> selectedContexts) {
        return check(rawQuery, intent, mainRewrittenQuery, selectedContexts, null);
    }

    /**
     * 判断当前检索到的 context 是否足够支持回答用户问题（Phase 6.5：含对话历史）。
     *
     * @param rawQuery            用户原始问题（意图锚点）
     * @param intent              用户问题意图
     * @param mainRewrittenQuery  主改写 query
     * @param selectedContexts    当前 top-k chunks
     * @param chatHistory         格式化的对话历史（可为 null）
     * @return ReflectionResult 包含充分性判断、置信度、缺失信息点、补充检索 query
     */
    public ReflectionResult check(String rawQuery, String intent, String mainRewrittenQuery,
                                   List<SearchResult> selectedContexts, String chatHistory) {
        logger.info("Reflection check: rawQuery='{}', intent={}, contextCount={}, hasHistory={}",
                rawQuery, intent, selectedContexts != null ? selectedContexts.size() : 0,
                chatHistory != null && !chatHistory.isBlank());

        if (selectedContexts == null || selectedContexts.isEmpty()) {
            ReflectionResult insufficient = new ReflectionResult();
            insufficient.setSufficient(false);
            insufficient.setConfidence(0.0);
            insufficient.getProblems().add("无检索结果，无法进行充分性判断");
            insufficient.getMissingAspects().add("全部信息");
            return insufficient;
        }

        String contextsText = formatContexts(selectedContexts);
        String prompt = buildPrompt(rawQuery, intent, mainRewrittenQuery, contextsText, chatHistory);

        try {
            double temperature = ragProperties.getReflection().getTemperature();
            String response = llmClient.chatSync(null, prompt, temperature);
            return parseReflectionResult(response);
        } catch (Exception e) {
            logger.error("Reflection LLM 调用失败: {}", e.getMessage(), e);
            return fallbackSufficient();
        }
    }

    private String formatContexts(List<SearchResult> contexts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            SearchResult c = contexts.get(i);
            sb.append("[chunk_id=").append(c.getFileMd5()).append("_").append(c.getChunkId()).append("]\n");
            sb.append("cross_score=").append(String.format("%.3f", c.getCrossScore())).append("\n");
            sb.append("retrieval_source=")
                    .append(c.getRetrievalSource() != null ? c.getRetrievalSource() : "unknown")
                    .append("\n");
            sb.append("content:\n").append(c.getTextContent() != null ? c.getTextContent() : "").append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String rawQuery, String intent, String mainRewrittenQuery,
                               String contextsText, String chatHistory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                你是一个 RAG 检索质量审查器，不负责回答用户问题。

                你的任务是判断当前检索到的 context 是否足够支持回答用户问题。

                请根据 raw_query、intent 和 selected_contexts 进行检查。

                你需要判断：
                1. context 是否与 raw_query 相关；
                2. context 是否覆盖回答该问题所需的关键信息点；
                3. 是否存在明显缺失的信息；
                4. 是否需要补充检索；
                5. 如果需要补充检索，请生成 1~3 个补充检索 query。

                要求：
                - 不要直接回答用户问题。
                - 不要编造 context 中不存在的事实。
                - 如果当前 context 只覆盖定义，但用户问的是实现方法，应判定为不充分。
                - 如果用户问的是公式、参数、位置等精确事实，而 context 没有直接证据，应判定为不充分。
                - supplementary_queries 应该面向检索系统，而不是面向用户。
                - supplementary_queries 应该尽量包含缺失信息点中的核心术语。
                - supplementary_queries 不要引入与 raw_query 无关的新主题。
                - 输出 JSON，不要输出其他内容。

                raw_query:
                %s

                intent:
                %s

                main_rewritten_query:
                %s

                """.formatted(rawQuery, intent, mainRewrittenQuery));

        // Phase 6.5: 有对话历史时，加入对话上下文用于跨轮次检查
        if (chatHistory != null && !chatHistory.isBlank()) {
            prompt.append("""
                    recent_conversation（最近对话，用于判断当前问题是否为追问或关联提问）:
                    %s

                    """.formatted(chatHistory));
        }

        prompt.append("""
                selected_contexts:
                %s

                请输出：
                {
                  "is_sufficient": true/false,
                  "confidence": 0.0-1.0,
                  "problems": [],
                  "missing_aspects": [],
                  "supplementary_queries": []
                }
                """.formatted(contextsText));

        return prompt.toString();
    }

    private ReflectionResult parseReflectionResult(String llmResponse) {
        try {
            // 从响应中提取 JSON（可能被包裹在 markdown code block 中）
            String json = extractJson(llmResponse);
            ReflectionResult result = objectMapper.readValue(json, ReflectionResult.class);
            logger.info("Reflection result: isSufficient={}, confidence={}, problems={}, supplementaryQueries={}",
                    result.isSufficient(), result.getConfidence(),
                    result.getProblems().size(), result.getSupplementaryQueries().size());
            return result;
        } catch (JsonProcessingException e) {
            logger.error("Reflection JSON 解析失败: {}", e.getMessage());
            return fallbackSufficient();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     * 处理可能被包裹在 ```json ... ``` 中的情况。
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }
        String trimmed = response.trim();
        // 去掉 markdown code block 包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n");
            if (start < 0) {
                return trimmed;
            }
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            } else {
                trimmed = trimmed.substring(start + 1).trim();
            }
        }
        // 找到第一个 { 到最后一个 }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    /**
     * Fallback: LLM 调用失败或 JSON 解析失败时，视为 context 足够，
     * 不触发补充检索，避免因系统故障造成延迟增加。
     */
    private ReflectionResult fallbackSufficient() {
        ReflectionResult result = new ReflectionResult();
        result.setSufficient(true);
        result.setConfidence(0.0);
        result.getProblems().add("Reflection LLM 调用或解析失败，默认视为 context 足够");
        return result;
    }
}
