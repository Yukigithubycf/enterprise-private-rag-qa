package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReflectionServiceTest {

    @Mock
    private RagLlmClient llmClient;

    private ReflectionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        service = new ReflectionService(llmClient, ragProperties, objectMapper);
    }

    @Test
    void shouldDetectSufficientContext() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": true,
                          "confidence": 0.85,
                          "problems": [],
                          "missing_aspects": [],
                          "supplementary_queries": []
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("如何实现 Query Rewrite？", "实现方法", "Query Rewrite 实现方法", contexts);

        assertTrue(result.isSufficient());
        assertEquals(0.85, result.getConfidence(), 0.01);
        assertTrue(result.getSupplementaryQueries().isEmpty());
    }

    @Test
    void shouldDetectInsufficientContext() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": false,
                          "confidence": 0.45,
                          "problems": ["缺少实现步骤"],
                          "missing_aspects": ["Prompt 模板", "输入输出格式"],
                          "supplementary_queries": ["Query Rewrite prompt 模板 JSON", "Query Rewrite 输入输出 schema"]
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("如何实现 Query Rewrite？", "实现方法", "Query Rewrite 实现方法", contexts);

        assertFalse(result.isSufficient());
        assertEquals(0.45, result.getConfidence(), 0.01);
        assertEquals(1, result.getProblems().size());
        assertEquals(2, result.getMissingAspects().size());
        assertEquals(2, result.getSupplementaryQueries().size());
    }

    @Test
    void shouldHandleEmptyContexts() {
        ReflectionResult result = service.check("test query", "概念解释", "test", List.of());

        assertFalse(result.isSufficient());
        assertEquals(0.0, result.getConfidence(), 0.01);
        assertFalse(result.getProblems().isEmpty());
    }

    @Test
    void shouldFallbackOnLlmFailure() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("LLM timeout"));

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("test", "概念解释", "test", contexts);

        // Fallback: 视为 context 足够，避免因系统故障增加延迟
        assertTrue(result.isSufficient());
        assertFalse(result.getProblems().isEmpty());
    }

    @Test
    void shouldParseJsonInMarkdownCodeBlock() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        ```json
                        {
                          "is_sufficient": true,
                          "confidence": 0.9,
                          "problems": [],
                          "missing_aspects": [],
                          "supplementary_queries": []
                        }
                        ```
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("test", "概念解释", "test", contexts);

        assertTrue(result.isSufficient());
        assertEquals(0.9, result.getConfidence(), 0.01);
    }

    @Test
    void shouldReturnSupplementaryQueriesLimitedCount() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": false,
                          "confidence": 0.3,
                          "problems": ["覆盖不足"],
                          "missing_aspects": ["A", "B", "C", "D"],
                          "supplementary_queries": ["q1", "q2", "q3", "q4"]
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("test", "实现方法", "test", contexts);

        assertFalse(result.isSufficient());
        assertEquals(4, result.getSupplementaryQueries().size());
    }

    @Test
    void shouldPassChatHistoryToLlmPrompt() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": true,
                          "confidence": 0.8,
                          "problems": [],
                          "missing_aspects": [],
                          "supplementary_queries": []
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        String chatHistory = "用户: 什么是 RRF？\n助手: RRF 是 Reciprocal Rank Fusion...\n用户: 它的公式是什么？";

        ReflectionResult result = service.check("它的公式是什么？", "概念解释",
                "RRF 公式", contexts, chatHistory);

        assertTrue(result.isSufficient());
        assertEquals(0.8, result.getConfidence(), 0.01);
    }

    @Test
    void shouldHandleNullChatHistory() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": true,
                          "confidence": 0.9,
                          "problems": [],
                          "missing_aspects": [],
                          "supplementary_queries": []
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("test", "概念解释", "test", contexts, null);

        assertTrue(result.isSufficient());
    }

    @Test
    void shouldHandleBlankChatHistory() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": true,
                          "confidence": 0.75,
                          "problems": [],
                          "missing_aspects": [],
                          "supplementary_queries": []
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        ReflectionResult result = service.check("test", "概念解释", "test", contexts, "   ");

        assertTrue(result.isSufficient());
    }

    @Test
    void shouldDetectInsufficientForFollowUpWithHistory() {
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("""
                        {
                          "is_sufficient": false,
                          "confidence": 0.3,
                          "problems": ["context 不包含公式细节"],
                          "missing_aspects": ["RRF 数学公式", "k 参数含义"],
                          "supplementary_queries": ["RRF 公式 1/(k+rank)", "RRF k 参数"]
                        }
                        """);

        List<SearchResult> contexts = buildContexts();
        String chatHistory = "用户: 什么是 RRF？\n助手: RRF 是 Reciprocal Rank Fusion，用于融合多路召回结果...";

        ReflectionResult result = service.check("它的公式是什么？", "概念解释",
                "RRF 公式", contexts, chatHistory);

        assertFalse(result.isSufficient());
        assertEquals(2, result.getMissingAspects().size());
        assertEquals(2, result.getSupplementaryQueries().size());
    }

    private List<SearchResult> buildContexts() {
        SearchResult r1 = new SearchResult("md5_1", 1, "Query Rewrite 是一种将用户查询改写为更适合检索的形式的技术", 0.9);
        r1.setRetrievalSource("rewrite_vector");
        r1.setCrossScore(0.91);

        SearchResult r2 = new SearchResult("md5_2", 2, "RRF 是一种融合多路召回结果的方法，使用公式 1/(k+rank)", 0.85);
        r2.setRetrievalSource("raw_bm25");
        r2.setCrossScore(0.87);

        return List.of(r1, r2);
    }
}
