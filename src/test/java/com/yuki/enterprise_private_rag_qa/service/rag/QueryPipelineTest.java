package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryPipelineTest {

    @Mock
    private RagLlmClient llmClient;

    private QueryNormalizer normalizer;
    private QueryCleaner cleaner;
    private IntentDetector intentDetector;
    private EntityExtractor entityExtractor;
    private QueryRewriter queryRewriter;
    private HydeGenerator hydeGenerator;
    private QueryPipeline pipeline;

    @BeforeEach
    void setUp() {
        normalizer = new QueryNormalizer();
        cleaner = new QueryCleaner();
        RagProperties ragProperties = new RagProperties();
        intentDetector = new IntentDetector(llmClient, ragProperties);
        ObjectMapper objectMapper = new ObjectMapper();
        entityExtractor = new EntityExtractor(llmClient, objectMapper);
        queryRewriter = new QueryRewriter(llmClient, objectMapper);
        hydeGenerator = new HydeGenerator(llmClient);
        pipeline = new QueryPipeline(normalizer, cleaner, intentDetector,
                entityExtractor, queryRewriter, hydeGenerator);
    }

    @Test
    void shouldBuildQueryInfoSuccessfully() {
        // Mock LLM responses
        when(llmClient.chatSync(anyString(), contains("entities"), anyDouble()))
                .thenReturn("{\"entities\": [\"Query Rewrite\", \"RAG\"], \"keywords\": [\"召回\", \"多路\"]}");
        when(llmClient.chatSync(anyString(), contains("rewritten"), anyDouble()))
                .thenReturn("""
                        {
                          "main_rewritten_query": "RAG 系统中多路召回的原因和实现方法",
                          "rewritten_queries": [
                            "RAG 多路召回的设计原理",
                            "multi-route retrieval design in RAG system",
                            "多路召回为什么能提升RAG效果"
                          ]
                        }""");
        when(llmClient.chatSync(anyString(), contains("HyDE"), anyDouble()))
                .thenReturn("多路召回是 RAG 系统中提升检索效果的核心策略。通过结合 BM25 关键词检索、Dense Vector 语义检索、查询改写后的多视角检索和 HyDE 假设文档检索，可以实现不同检索方式的互补。");

        String rawQuery = "为什么RAG需要多路召回？";

        // Execute
        QueryInfo info = pipeline.process(rawQuery, null);

        // Verify basic fields
        assertEquals(rawQuery, info.getRawQuery());
        assertNotNull(info.getNormalizedQuery());
        assertNotNull(info.getCleanedQuery());
        assertNotNull(info.getIntent());
        assertEquals("原因分析", info.getIntent());

        // Verify LLM-based fields
        assertFalse(info.getEntities().isEmpty());
        assertFalse(info.getKeywords().isEmpty());
        assertNotNull(info.getMainRewrittenQuery());
        assertFalse(info.getRewrittenQueries().isEmpty());
        assertNotNull(info.getHydeDocument());
    }

    @Test
    void shouldFallbackWhenLlmFails() {
        // Mock LLM failures
        when(llmClient.chatSync(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("LLM service unavailable"));

        String rawQuery = "为什么RAG需要多路召回？";

        // Execute - should not throw
        QueryInfo info = pipeline.process(rawQuery, null);

        // Verify fallback values
        assertEquals(rawQuery, info.getRawQuery());
        assertNotNull(info.getNormalizedQuery());
        assertNull(info.getHydeDocument());
        // Fallback rewrites to raw query
        assertNotNull(info.getMainRewrittenQuery());
    }

    @Test
    void shouldHandleFullwidthQuery() {
        when(llmClient.chatSync(anyString(), contains("entities"), anyDouble()))
                .thenReturn("{\"entities\": [], \"keywords\": []}");
        when(llmClient.chatSync(anyString(), contains("rewritten"), anyDouble()))
                .thenReturn("""
                        {
                          "main_rewritten_query": "Query Rewrite 导致召回结果跑偏的原因",
                          "rewritten_queries": [
                            "Query Rewrite 导致召回结果跑偏的原因",
                            "LLM query rewriting causing retrieval drift"
                          ]
                        }""");
        when(llmClient.chatSync(anyString(), contains("HyDE"), anyDouble()))
                .thenReturn("Query Rewrite 可能导致 RAG 召回结果偏题，常见原因包括改写时丢失原始问题中的核心实体。");

        String rawQuery = "请问一下，Ｑｕｅｒｙ　Ｒｅｗｒｉｔｅ 为什么会让召回结果跑偏呢？";

        QueryInfo info = pipeline.process(rawQuery, null);

        assertEquals(rawQuery, info.getRawQuery());
        // Normalized should have halfwidth characters
        assertTrue(info.getNormalizedQuery().contains("Query Rewrite"));
        assertEquals("原因分析", info.getIntent());
    }
}
