package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.service.HybridSearchService;
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
class SupplementaryRetrieverTest {

    @Mock
    private HybridSearchService hybridSearchService;

    private SupplementaryRetriever retriever;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        retriever = new SupplementaryRetriever(hybridSearchService, ragProperties);
    }

    @Test
    void shouldRetrieveForAllSupplementaryQueries() {
        when(hybridSearchService.keywordSearchByText(anyString(), any(), anyInt(), eq(false)))
                .thenAnswer(inv -> {
                    String query = inv.getArgument(0);
                    return List.of(buildResult("bm25_" + query));
                });
        when(hybridSearchService.semanticSearchByText(anyString(), any(), anyInt()))
                .thenAnswer(inv -> {
                    String query = inv.getArgument(0);
                    return List.of(buildResult("vec_" + query));
                });

        List<String> queries = List.of("q1", "q2");
        List<List<SearchResult>> results = retriever.retrieve(queries, buildDummyFilter());

        // 2 queries × 2 routes each = 4 result lists
        assertEquals(4, results.size());
        // Verify retrieval source tagging
        assertTrue(results.stream().anyMatch(list ->
                list.get(0).getRetrievalSource().equals("reflection_bm25")));
        assertTrue(results.stream().anyMatch(list ->
                list.get(0).getRetrievalSource().equals("reflection_vector")));
    }

    @Test
    void shouldAssignRankAndQueryUsed() {
        when(hybridSearchService.keywordSearchByText(anyString(), any(), anyInt(), eq(false)))
                .thenReturn(List.of(
                        buildResult("r1"),
                        buildResult("r2"),
                        buildResult("r3")
                ));
        when(hybridSearchService.semanticSearchByText(anyString(), any(), anyInt()))
                .thenReturn(List.of(buildResult("r1")));

        List<String> queries = List.of("supplementary query");
        List<List<SearchResult>> results = retriever.retrieve(queries, buildDummyFilter());

        // First list is BM25 with 3 results
        List<SearchResult> bm25List = results.get(0);
        assertEquals(1, bm25List.get(0).getRank());
        assertEquals(2, bm25List.get(1).getRank());
        assertEquals(3, bm25List.get(2).getRank());
        assertEquals("supplementary query", bm25List.get(0).getQueryUsed());
    }

    @Test
    void shouldHandleEmptyQueries() {
        assertTrue(retriever.retrieve(null, buildDummyFilter()).isEmpty());
        assertTrue(retriever.retrieve(List.of(), buildDummyFilter()).isEmpty());
    }

    @Test
    void shouldSkipBlankQueries() {
        when(hybridSearchService.keywordSearchByText(eq("valid query"), any(), anyInt(), eq(false)))
                .thenReturn(List.of(buildResult("bm25_valid")));
        when(hybridSearchService.semanticSearchByText(eq("valid query"), any(), anyInt()))
                .thenReturn(List.of(buildResult("vec_valid")));

        List<String> queries = List.of("  ", "valid query");
        List<List<SearchResult>> results = retriever.retrieve(queries, buildDummyFilter());

        // Only 2 lists for "valid query" (blank skipped)
        assertEquals(2, results.size());
    }

    @Test
    void shouldHandleRetrievalFailureGracefully() {
        when(hybridSearchService.keywordSearchByText(anyString(), any(), anyInt(), eq(false)))
                .thenThrow(new RuntimeException("ES unavailable"));
        when(hybridSearchService.semanticSearchByText(anyString(), any(), anyInt()))
                .thenReturn(List.of(buildResult("vec_ok")));

        List<String> queries = List.of("test");
        List<List<SearchResult>> results = retriever.retrieve(queries, buildDummyFilter());

        // BM25 failed but Vector succeeded → 1 list
        assertEquals(1, results.size());
        assertEquals("reflection_vector", results.get(0).get(0).getRetrievalSource());
    }

    private SearchResult buildResult(String text) {
        return new SearchResult("md5_" + text, 1, "content: " + text, 0.8);
    }

    private Query buildDummyFilter() {
        return Query.of(q -> q.matchAll(m -> m));
    }
}
