package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
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
class ReflectionPipelineTest {

    @Mock
    private ReflectionGate reflectionGate;
    @Mock
    private ReflectionService reflectionService;
    @Mock
    private SupplementaryRetriever supplementaryRetriever;
    @Mock
    private CandidateFilterPipeline candidateFilterPipeline;

    private RagProperties ragProperties;
    private ReflectionPipeline pipeline;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        pipeline = new ReflectionPipeline(reflectionGate, reflectionService,
                supplementaryRetriever, candidateFilterPipeline, ragProperties);
    }

    @Test
    void shouldReturnInitialResultsWhenGateDisabled() {
        when(reflectionGate.shouldReflect("查找定位", 8)).thenReturn(false);

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);
        List<List<SearchResult>> originalLists = buildOriginalLists();

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "查找定位", "test", originalLists, initialResult, buildDummyFilter(), null);

        assertEquals(initialResult.finalDocs(), result.finalDocs());
        assertFalse(result.usedSupplementaryRetrieval());
        assertNull(result.reflectionResult());
        verify(reflectionService, never()).check(anyString(), anyString(), anyString(), anyList(), any());
    }

    @Test
    void shouldReturnInitialResultsWhenContextSufficient() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);

        ReflectionResult sufficient = new ReflectionResult();
        sufficient.setSufficient(true);
        sufficient.setConfidence(0.85);
        when(reflectionService.check(eq("test query"), eq("实现方法"), eq("test"), anyList(), isNull()))
                .thenReturn(sufficient);

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);
        List<List<SearchResult>> originalLists = buildOriginalLists();

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", originalLists, initialResult, buildDummyFilter(), null);

        assertEquals(initialResult.finalDocs(), result.finalDocs());
        assertFalse(result.usedSupplementaryRetrieval());
        assertTrue(result.reflectionResult().isSufficient());
    }

    @Test
    void shouldTriggerSupplementaryRetrievalWhenInsufficient() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);
        when(reflectionGate.getMaxReflectionRounds()).thenReturn(1);

        ReflectionResult insufficient = new ReflectionResult();
        insufficient.setSufficient(false);
        insufficient.setConfidence(0.4);
        insufficient.getSupplementaryQueries().add("supplementary q1");
        insufficient.getSupplementaryQueries().add("supplementary q2");
        when(reflectionService.check(eq("test query"), eq("实现方法"), eq("test"), anyList(), isNull()))
                .thenReturn(insufficient);

        // Supplementary retrieval returns 2 lists
        List<SearchResult> suppList1 = buildResultList("reflection_bm25", 3);
        List<SearchResult> suppList2 = buildResultList("reflection_vector", 2);
        when(supplementaryRetriever.retrieve(anyList(), any()))
                .thenReturn(List.of(suppList1, suppList2));

        // Re-ranking returns new final docs
        CandidateFilterPipeline.FilterResult reRankedResult = buildFilterResult(6);
        when(candidateFilterPipeline.filter(anyString(), anyString(), anyList()))
                .thenReturn(reRankedResult);

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);
        List<List<SearchResult>> originalLists = buildOriginalLists();

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", originalLists, initialResult, buildDummyFilter(), null);

        assertTrue(result.usedSupplementaryRetrieval());
        assertEquals(reRankedResult.finalDocs(), result.finalDocs());
        assertEquals(2, result.supplementaryQueries().size());
        assertEquals("supplementary q1", result.supplementaryQueries().get(0));
    }

    @Test
    void shouldNotTriggerRetrievalWhenMaxRoundsZero() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);
        when(reflectionGate.getMaxReflectionRounds()).thenReturn(0);

        ReflectionResult insufficient = new ReflectionResult();
        insufficient.setSufficient(false);
        insufficient.getSupplementaryQueries().add("q1");
        when(reflectionService.check(anyString(), anyString(), anyString(), anyList(), isNull()))
                .thenReturn(insufficient);

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", buildOriginalLists(), initialResult, buildDummyFilter(), null);

        assertFalse(result.usedSupplementaryRetrieval());
        verify(supplementaryRetriever, never()).retrieve(anyList(), any());
        verify(candidateFilterPipeline, never()).filter(anyString(), anyString(), anyList());
    }

    @Test
    void shouldNotTriggerRetrievalWhenNoSupplementaryQueries() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);
        when(reflectionGate.getMaxReflectionRounds()).thenReturn(1);

        ReflectionResult insufficient = new ReflectionResult();
        insufficient.setSufficient(false);
        // No supplementary queries
        when(reflectionService.check(anyString(), anyString(), anyString(), anyList(), isNull()))
                .thenReturn(insufficient);

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", buildOriginalLists(), initialResult, buildDummyFilter(), null);

        assertFalse(result.usedSupplementaryRetrieval());
        verify(supplementaryRetriever, never()).retrieve(anyList(), any());
    }

    @Test
    void shouldLimitSupplementaryQueriesTo3() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);
        when(reflectionGate.getMaxReflectionRounds()).thenReturn(1);

        ReflectionResult insufficient = new ReflectionResult();
        insufficient.setSufficient(false);
        for (int i = 0; i < 5; i++) {
            insufficient.getSupplementaryQueries().add("q" + i);
        }
        when(reflectionService.check(anyString(), anyString(), anyString(), anyList(), isNull()))
                .thenReturn(insufficient);

        // Supplementary returns non-empty so re-ranking triggers
        List<SearchResult> suppList = buildResultList("reflection_bm25", 2);
        when(supplementaryRetriever.retrieve(anyList(), any())).thenReturn(List.of(suppList));
        when(candidateFilterPipeline.filter(anyString(), anyString(), anyList()))
                .thenReturn(buildInitialResult(8));

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", buildOriginalLists(), initialResult, buildDummyFilter(), null);

        // Limited to 3 queries
        verify(supplementaryRetriever).retrieve(argThat(list -> list.size() == 3), any());
    }

    @Test
    void shouldHandleSupplementaryRetrievalEmptyResult() {
        when(reflectionGate.shouldReflect("实现方法", 8)).thenReturn(true);
        when(reflectionGate.getMaxReflectionRounds()).thenReturn(1);

        ReflectionResult insufficient = new ReflectionResult();
        insufficient.setSufficient(false);
        insufficient.getSupplementaryQueries().add("q1");
        when(reflectionService.check(anyString(), anyString(), anyString(), anyList(), isNull()))
                .thenReturn(insufficient);

        when(supplementaryRetriever.retrieve(anyList(), any())).thenReturn(List.of());

        CandidateFilterPipeline.FilterResult initialResult = buildInitialResult(8);

        ReflectionPipeline.ReflectionPipelineResult result = pipeline.reflect(
                "test query", "实现方法", "test", buildOriginalLists(), initialResult, buildDummyFilter(), null);

        assertTrue(result.usedSupplementaryRetrieval());
        assertEquals(initialResult.finalDocs(), result.finalDocs());
        // Re-ranking should not be called when supplementary retrieval is empty
        verify(candidateFilterPipeline, never()).filter(anyString(), anyString(), anyList());
    }

    private CandidateFilterPipeline.FilterResult buildInitialResult(int count) {
        List<SearchResult> docs = buildResultList("initial", count);
        return new CandidateFilterPipeline.FilterResult(docs, MmrDecision.disable("test"), docs);
    }

    private CandidateFilterPipeline.FilterResult buildFilterResult(int count) {
        List<SearchResult> docs = buildResultList("reranked", count);
        return new CandidateFilterPipeline.FilterResult(docs, MmrDecision.enable("test"), docs);
    }

    private List<List<SearchResult>> buildOriginalLists() {
        List<List<SearchResult>> lists = new ArrayList<>();
        lists.add(buildResultList("raw_bm25", 5));
        lists.add(buildResultList("raw_vector", 5));
        return lists;
    }

    private List<SearchResult> buildResultList(String source, int count) {
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult r = new SearchResult("md5_" + source + "_" + i, i, "content " + source + " " + i, 0.9 - i * 0.1);
            r.setRetrievalSource(source);
            r.setRank(i + 1);
            r.setCrossScore(0.85 - i * 0.05);
            list.add(r);
        }
        return list;
    }

    private Query buildDummyFilter() {
        return Query.of(q -> q.matchAll(m -> m));
    }
}
