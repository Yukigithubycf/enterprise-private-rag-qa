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
 * LLM 驱动的多版本查询改写。
 * 生成 main_rewritten_query 和多个 rewritten_queries（3~5个），
 * 覆盖中文完整问题式、英文完整问题式、保守改写式和术语补全式。
 */
@Service
public class QueryRewriter {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SYSTEM_PROMPT = "你是一个严格的 RAG Query Rewrite 模块。只输出 JSON，不输出其他内容。";

    private static final String PROMPT_TEMPLATE = """
            你是一个严格的 RAG Query Rewrite 模块。

            你的任务是把用户问题改写成适合检索系统使用的 query。

            你不能回答问题。
            你不能添加用户问题和对话历史中不存在的事实。
            你不能改变用户原始意图。
            你必须保留核心实体。

            输入信息：

            raw_query:
            {raw_query}

            normalized_query:
            {normalized_query}

            cleaned_query:
            {cleaned_query}

            chat_history:
            {chat_history}

            intent:
            {intent}

            entities:
            {entities}

            keywords:
            {keywords}

            请完成以下任务：
            1. 生成一个 main_rewritten_query，要求语义完整、实体明确、最贴近原始意图。
            2. 生成 3 到 5 个 rewritten_queries，覆盖中文完整问题式、英文完整问题式、保守改写式和术语补全式。
            3. 不要生成 keyword_queries。
            4. 不要生成 expanded_terms。
            5. 不要引入不存在的方法名、论文名、数据集名、API 名或实验结论。
            6. 输出 JSON，不要输出任何解释性文字。

            输出格式：
            {
              "main_rewritten_query": "",
              "rewritten_queries": []
            }""";

    private final RagLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public QueryRewriter(RagLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行查询改写。
     *
     * @param rawQuery       原始 query
     * @param normalizedQuery 规范化 query
     * @param cleanedQuery   BM25 清洗后 query
     * @param chatHistory    对话历史
     * @param intent         意图
     * @param entities       实体列表
     * @param keywords       关键词列表
     * @return RewriteResult 包含 main_rewritten_query 和 rewritten_queries
     */
    public RewriteResult rewrite(String rawQuery, String normalizedQuery, String cleanedQuery,
                                  String chatHistory, String intent,
                                  List<String> entities, List<String> keywords) {
        try {
            String entitiesStr = entities != null ? String.join(", ", entities) : "";
            String keywordsStr = keywords != null ? String.join(", ", keywords) : "";
            String historyText = chatHistory != null ? chatHistory : "（无对话历史）";

            String prompt = PROMPT_TEMPLATE
                    .replace("{raw_query}", rawQuery)
                    .replace("{normalized_query}", normalizedQuery)
                    .replace("{cleaned_query}", cleanedQuery)
                    .replace("{chat_history}", historyText)
                    .replace("{intent}", intent != null ? intent : "通用问答")
                    .replace("{entities}", entitiesStr)
                    .replace("{keywords}", keywordsStr);

            logger.debug("Query rewrite prompt for: {}", rawQuery);
            String response = llmClient.chatSync(SYSTEM_PROMPT, prompt, 0.2);

            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);

            String mainQuery = root.path("main_rewritten_query").asText("");
            List<String> rewritten = new ArrayList<>();
            if (root.has("rewritten_queries") && root.get("rewritten_queries").isArray()) {
                root.get("rewritten_queries").forEach(n -> {
                    String q = n.asText().trim();
                    if (!q.isBlank()) {
                        rewritten.add(q);
                    }
                });
            }

            // 校验：如果 LLM 输出为空，fallback 到 raw query
            if (mainQuery.isBlank()) {
                mainQuery = rawQuery;
                logger.warn("Query rewrite returned empty main_rewritten_query, fallback to raw_query");
            }
            if (rewritten.isEmpty()) {
                rewritten.add(rawQuery);
                logger.warn("Query rewrite returned empty rewritten_queries, fallback to raw_query");
            }

            logger.debug("Rewrite result - main: {}, rewritten count: {}", mainQuery, rewritten.size());
            return new RewriteResult(mainQuery, rewritten);

        } catch (Exception e) {
            logger.error("Query rewrite failed: {}", e.getMessage(), e);
            // Fallback: 直接使用 raw query
            return new RewriteResult(rawQuery, List.of(rawQuery));
        }
    }

    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    /**
     * Query Rewrite 结果。
     */
    public record RewriteResult(String mainRewrittenQuery, List<String> rewrittenQueries) {
    }
}
