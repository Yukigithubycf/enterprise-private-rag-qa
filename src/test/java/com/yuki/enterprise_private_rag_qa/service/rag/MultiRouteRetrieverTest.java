package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import com.yuki.enterprise_private_rag_qa.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiRouteRetrieverTest {

    @Mock
    private HybridSearchService searchService;

    private RagProperties ragProperties;
    private MultiRouteRetriever retriever;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        // Use default config from RagProperties
        retriever = new MultiRouteRetriever(searchService, ragProperties);

        // Mock permission filter
        when(searchService.buildPermissionFilter(anyString()))
                .thenReturn(Query.of(q -> q.matchAll(m -> m)));  // dummy filter
    }

    @Test
    void shouldReturnTaggedResultsForAllRoutes() {
        QueryInfo queryInfo = buildFullQueryInfo();

        when(searchService.keywordSearchByText(anyString(), any(Query.class), anyInt(), anyBoolean()))
                .thenAnswer(inv -> createMockResults("doc1", 3));
        when(searchService.semanticSearchByText(anyString(), any(Query.class), anyInt()))
                .thenAnswer(inv -> createMockResults("doc2", 3));

        List<List<SearchResult>> resultLists = retriever.retrieve(queryInfo, "user1");

        assertNotNull(resultLists);
        assertTrue(resultLists.size() >= 6, "Should have at least 6 route result lists");

        // Verify all results are tagged
        for (List<SearchResult> results : resultLists) {
            for (SearchResult r : results) {
                assertNotNull(r.getRetrievalSource(), "Every result should have retrievalSource");
                assertTrue(r.getRank() > 0, "Every result should have rank > 0");
                assertNotNull(r.getQueryUsed(), "Every result should have queryUsed");
            }
        }
    }

    @Test
    void shouldSkipEmptyResults() {
        QueryInfo queryInfo = buildFullQueryInfo();

        when(searchService.keywordSearchByText(anyString(), any(Query.class), anyInt(), anyBoolean()))
                .thenAnswer(inv -> List.of());
        when(searchService.semanticSearchByText(anyString(), any(Query.class), anyInt()))
                .thenAnswer(inv -> List.of());

        List<List<SearchResult>> resultLists = retriever.retrieve(queryInfo, "user1");

        // Should not throw, but may have zero result lists (empty routes are filtered)
        assertNotNull(resultLists);
    }

    @Test
    void shouldSkipNullHydeRoute() {
        QueryInfo queryInfo = buildFullQueryInfo();
        queryInfo.setHydeDocument(null); // No HyDE

        when(searchService.keywordSearchByText(anyString(), any(Query.class), anyInt(), anyBoolean()))
                .thenAnswer(inv -> createMockResults("doc1", 2));
        when(searchService.semanticSearchByText(anyString(), any(Query.class), anyInt()))
                .thenAnswer(inv -> createMockResults("doc2", 2));

        List<List<SearchResult>> resultLists = retriever.retrieve(queryInfo, "user1");

        // Should still work without HyDE route
        boolean hasHydeRoute = resultLists.stream()
                .flatMap(List::stream)
                .anyMatch(r -> "hyde_vector".equals(r.getRetrievalSource()));
        assertFalse(hasHydeRoute, "Should not have hyde_vector route when hydeDocument is null");
    }

    @Test
    void shouldTagRetrievalSourcesCorrectly() {
        QueryInfo queryInfo = buildFullQueryInfo();

        when(searchService.keywordSearchByText(anyString(), any(Query.class), anyInt(), anyBoolean()))
                .thenAnswer(inv -> createMockResults("kw", 1));
        when(searchService.semanticSearchByText(anyString(), any(Query.class), anyInt()))
                .thenAnswer(inv -> createMockResults("vec", 1));

        List<List<SearchResult>> resultLists = retriever.retrieve(queryInfo, "user1");

        List<String> sources = resultLists.stream()
                .flatMap(List::stream)
                .map(SearchResult::getRetrievalSource)
                .distinct()
                .sorted()
                .toList();

        System.out.println("Actual sources: " + sources);

        assertTrue(sources.contains("raw_bm25"), "Should contain raw_bm25. Actual: " + sources);
        assertTrue(sources.contains("raw_vector"), "Should contain raw_vector. Actual: " + sources);
        assertTrue(sources.contains("rewrite_vector"), "Should contain rewrite_vector. Actual: " + sources);
        assertTrue(sources.contains("rewrite_bm25"), "Should contain rewrite_bm25. Actual: " + sources);
    }

    private QueryInfo buildFullQueryInfo() {
        QueryInfo info = new QueryInfo();
        info.setRawQuery("为什么RAG需要多路召回？");
        info.setNormalizedQuery("为什么RAG需要多路召回?");
        info.setCleanedQuery("RAG 多路召回 原因");
        info.setIntent("原因分析");
        info.setEntities(List.of("RAG", "多路召回"));
        info.setKeywords(List.of("检索", "召回"));
        info.setMainRewrittenQuery("RAG 系统中多路召回的原因");
        info.setRewrittenQueries(List.of(
                "RAG 多路召回的设计原因",
                "multi-route retrieval in RAG why needed",
                "多路召回相对于单路召回的优势"
        ));
        info.setHydeDocument("多路召回是 RAG 系统的核心策略，通过组合 BM25 关键词检索和 Dense Vector 语义检索等多条检索链路，可以互补各自的局限性，提高召回覆盖率和最终答案质量。");
        return info;
    }

    private List<SearchResult> createMockResults(String prefix, int count) {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult r = new SearchResult(
                    prefix + "_md5_" + i,
                    i,
                    prefix + "_chunk content " + i,
                    0.8 - i * 0.1
            );
            results.add(r);
        }
        return results;
    }
}
