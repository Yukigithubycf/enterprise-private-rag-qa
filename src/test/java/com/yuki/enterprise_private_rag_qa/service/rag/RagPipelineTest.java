package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagPipelineTest {

    @Mock
    private QueryPipeline queryPipeline;
    @Mock
    private MultiRouteRetriever multiRouteRetriever;
    @Mock
    private CandidateFilterPipeline candidateFilterPipeline;
    @Mock
    private ReflectionPipeline reflectionPipeline;
    @Mock
    private HybridSearchService hybridSearchService;
    @Mock
    private ParentAggregator parentAggregator;

    private RagProperties ragProperties;
    private RagPipeline pipeline;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        lenient().when(parentAggregator.aggregate(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        pipeline = new RagPipeline(queryPipeline, multiRouteRetriever,
                candidateFilterPipeline, reflectionPipeline, parentAggregator,
                hybridSearchService, ragProperties);
    }

    @Test
    void shouldExecuteFullPipelineSuccessfully() {
        // Stage 1: QueryPipeline
        QueryInfo queryInfo = buildQueryInfo();
        when(queryPipeline.process(eq("test query"), isNull())).thenReturn(queryInfo);

        // Stage 2: MultiRouteRetriever
        List<List<SearchResult>> resultLists = buildResultLists();
        when(multiRouteRetriever.retrieve(queryInfo, "user1")).thenReturn(resultLists);

        // Stage 3: CandidateFilterPipeline
        List<SearchResult> finalDocs = buildDocList("final", 8);
        CandidateFilterPipeline.FilterResult filterResult = new CandidateFilterPipeline.FilterResult(
                buildDocList("cross", 20), MmrDecision.enable("test"), finalDocs);
        when(candidateFilterPipeline.filter(eq("test query"), eq("实现方法"), eq(resultLists)))
                .thenReturn(filterResult);

        // Stage 4: ReflectionPipeline
        Query dummyFilter = Query.of(q -> q.matchAll(m -> m));
        when(hybridSearchService.buildPermissionFilter("user1")).thenReturn(dummyFilter);

        ReflectionPipeline.ReflectionPipelineResult reflectionResult =
                new ReflectionPipeline.ReflectionPipelineResult(finalDocs, null, false, List.of());
        when(reflectionPipeline.reflect(eq("test query"), eq("实现方法"), eq("main rewritten"),
                eq(resultLists), eq(filterResult), eq(dummyFilter), isNull()))
                .thenReturn(reflectionResult);

        // Execute
        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertNotNull(result);
        assertEquals(finalDocs, result.finalDocs());
        assertEquals(queryInfo, result.queryInfo());
        assertEquals(MmrDecision.enable("test"), result.mmrDecision());
        assertFalse(result.usedSupplementaryRetrieval());
        assertFalse(result.isFallback());

        verify(queryPipeline).process("test query", null);
        verify(multiRouteRetriever).retrieve(queryInfo, "user1");
        verify(candidateFilterPipeline).filter("test query", "实现方法", resultLists);
        verify(reflectionPipeline).reflect(eq("test query"), eq("实现方法"), eq("main rewritten"),
                eq(resultLists), eq(filterResult), eq(dummyFilter), isNull());
    }

    @Test
    void shouldPropagateSupplementaryRetrievalFlag() {
        QueryInfo queryInfo = buildQueryInfo();
        when(queryPipeline.process(anyString(), isNull())).thenReturn(queryInfo);

        List<List<SearchResult>> resultLists = buildResultLists();
        when(multiRouteRetriever.retrieve(any(), anyString())).thenReturn(resultLists);

        CandidateFilterPipeline.FilterResult filterResult = new CandidateFilterPipeline.FilterResult(
                buildDocList("cross", 20), MmrDecision.disable("test"), buildDocList("final", 5));
        when(candidateFilterPipeline.filter(anyString(), anyString(), anyList())).thenReturn(filterResult);

        when(hybridSearchService.buildPermissionFilter(anyString()))
                .thenReturn(Query.of(q -> q.matchAll(m -> m)));

        ReflectionPipeline.ReflectionPipelineResult reflectionResult =
                new ReflectionPipeline.ReflectionPipelineResult(
                        buildDocList("re-ranked", 8),
                        buildReflectionResult(),
                        true,
                        List.of("supplementary q1", "supplementary q2"));
        when(reflectionPipeline.reflect(anyString(), anyString(), anyString(), anyList(), any(), any(), isNull()))
                .thenReturn(reflectionResult);

        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertTrue(result.usedSupplementaryRetrieval());
        assertEquals(2, result.supplementaryQueries().size());
        assertEquals("supplementary q1", result.supplementaryQueries().get(0));
    }

    @Test
    void shouldFallbackToLegacyOnPipelineFailure() {
        when(queryPipeline.process(anyString(), isNull()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        List<SearchResult> legacyResults = buildDocList("legacy", 5);
        when(hybridSearchService.searchWithPermission(eq("test query"), eq("user1"), anyInt()))
                .thenReturn(legacyResults);

        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertTrue(result.isFallback());
        assertEquals(legacyResults, result.finalDocs());
        assertNull(result.queryInfo());
        // Verify final ranks assigned
        assertEquals(1, legacyResults.get(0).getFinalRank());
    }

    @Test
    void shouldHandleTotalFailureGracefully() {
        when(queryPipeline.process(anyString(), isNull()))
                .thenThrow(new RuntimeException("LLM unavailable"));
        when(hybridSearchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("ES unavailable"));

        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertTrue(result.isFallback());
        assertTrue(result.finalDocs().isEmpty());
    }

    @Test
    void shouldFailAtStage2AndFallback() {
        QueryInfo queryInfo = buildQueryInfo();
        when(queryPipeline.process(anyString(), isNull())).thenReturn(queryInfo);
        when(multiRouteRetriever.retrieve(any(), anyString()))
                .thenThrow(new RuntimeException("All routes failed"));

        List<SearchResult> legacyResults = buildDocList("legacy", 3);
        when(hybridSearchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(legacyResults);

        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertTrue(result.isFallback());
        assertEquals(3, result.finalDocs().size());
    }

    @Test
    void shouldIncludeQueryInfoInResult() {
        QueryInfo queryInfo = buildQueryInfo();
        queryInfo.setIntent("原因分析");
        queryInfo.getEntities().add("RRF");
        when(queryPipeline.process(anyString(), isNull())).thenReturn(queryInfo);

        List<List<SearchResult>> resultLists = buildResultLists();
        when(multiRouteRetriever.retrieve(any(), anyString())).thenReturn(resultLists);

        CandidateFilterPipeline.FilterResult filterResult = new CandidateFilterPipeline.FilterResult(
                buildDocList("cross", 15), MmrDecision.enable("test"), buildDocList("final", 8));
        when(candidateFilterPipeline.filter(anyString(), anyString(), anyList())).thenReturn(filterResult);

        when(hybridSearchService.buildPermissionFilter(anyString()))
                .thenReturn(Query.of(q -> q.matchAll(m -> m)));

        ReflectionPipeline.ReflectionPipelineResult reflResult =
                new ReflectionPipeline.ReflectionPipelineResult(
                        buildDocList("final", 8), null, false, List.of());
        when(reflectionPipeline.reflect(anyString(), anyString(), anyString(), anyList(), any(), any(), isNull()))
                .thenReturn(reflResult);

        RagPipeline.RagResult result = pipeline.execute("test query", "user1", null);

        assertEquals("原因分析", result.queryInfo().getIntent());
        assertTrue(result.queryInfo().getEntities().contains("RRF"));
    }

    private QueryInfo buildQueryInfo() {
        QueryInfo info = new QueryInfo();
        info.setRawQuery("test query");
        info.setNormalizedQuery("test query");
        info.setCleanedQuery("test query");
        info.setIntent("实现方法");
        info.setMainRewrittenQuery("main rewritten");
        info.setRewrittenQueries(List.of("rewritten 1", "rewritten 2"));
        return info;
    }

    private List<List<SearchResult>> buildResultLists() {
        List<List<SearchResult>> lists = new ArrayList<>();
        lists.add(buildDocList("raw_bm25", 5));
        lists.add(buildDocList("raw_vector", 5));
        return lists;
    }

    private List<SearchResult> buildDocList(String source, int count) {
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult r = new SearchResult("md5_" + source + "_" + i, i,
                    "content " + source + " " + i, 0.9 - i * 0.1);
            r.setRetrievalSource(source);
            r.setRank(i + 1);
            list.add(r);
        }
        return list;
    }

    private ReflectionResult buildReflectionResult() {
        ReflectionResult r = new ReflectionResult();
        r.setSufficient(false);
        r.setConfidence(0.4);
        r.getProblems().add("missing info");
        return r;
    }
}
