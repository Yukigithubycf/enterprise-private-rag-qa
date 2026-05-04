package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandidateFilterPipelineTest {

    @Mock
    private EmbeddingClient embeddingClient;

    private CandidateFilterPipeline pipeline;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        DeduplicationService dedup = new DeduplicationService();
        RrfFusionService rrf = new RrfFusionService();
        CrossEncoderReranker crossEncoder = new CrossEncoderReranker(ragProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        MmrGate mmrGate = new MmrGate(ragProperties, embeddingClient);
        MmrFilter mmrFilter = new MmrFilter(embeddingClient);

        pipeline = new CandidateFilterPipeline(dedup, rrf, crossEncoder, mmrGate, mmrFilter, ragProperties);
    }

    @Test
    void shouldProduceFinalTopKForPrecisionIntent() {
        List<List<SearchResult>> resultLists = buildMultiRouteResults();
        // Precision intent → MMR disabled → direct top-K
        CandidateFilterPipeline.FilterResult result = pipeline.filter("test query", "查找定位", resultLists);

        assertNotNull(result.finalDocs());
        assertFalse(result.finalDocs().isEmpty());
        assertFalse(result.mmrDecision().isUseMmr(), "MMR should be disabled for precision intent");
        assertEquals(ragProperties.getFinalSelection().getTopK(), result.finalDocs().size());
        // Verify cross scores assigned
        for (SearchResult doc : result.finalDocs()) {
            assertTrue(doc.getCrossScore() > 0, "cross_score should be assigned");
            assertTrue(doc.getFinalRank() > 0, "final_rank should be assigned");
        }
    }

    @Test
    void shouldEnableMmrForMultiAngleIntent() {
        List<List<SearchResult>> resultLists = buildMultiRouteResults();

        // Mock embeddings for MMR
        when(embeddingClient.embed(anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> texts = inv.getArgument(0);
                    List<float[]> embeddings = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) {
                        float[] emb = new float[4];
                        emb[0] = (float) (i % 3) / 3.0f;
                        emb[1] = (float) ((i + 1) % 3) / 3.0f;
                        emb[2] = 0.1f;
                        emb[3] = 0.5f;
                        embeddings.add(emb);
                    }
                    return embeddings;
                });

        CandidateFilterPipeline.FilterResult result = pipeline.filter("test query", "实现方法", resultLists);

        assertNotNull(result.finalDocs());
        assertTrue(result.mmrDecision().isUseMmr(), "MMR should be enabled for multi-angle intent");
    }

    @Test
    void shouldHandleSmallResultSet() {
        // Only 2 results → RRF top-N should handle this gracefully
        List<SearchResult> route1 = new ArrayList<>();
        route1.add(makeResult("md5_A", 1, 0.9, "raw_bm25", 1));
        route1.add(makeResult("md5_B", 2, 0.8, "raw_bm25", 2));

        List<List<SearchResult>> resultLists = List.of(route1);
        CandidateFilterPipeline.FilterResult result = pipeline.filter("test", "通用问答", resultLists);

        assertNotNull(result.finalDocs());
        assertTrue(result.finalDocs().size() <= 2);
    }

    @Test
    void shouldAssignAllRankFields() {
        List<List<SearchResult>> resultLists = buildMultiRouteResults();

        CandidateFilterPipeline.FilterResult result = pipeline.filter("test query", "通用问答", resultLists);

        for (SearchResult doc : result.crossRankedDocs()) {
            assertTrue(doc.getRrfScore() > 0 || doc.getRrfRank() > 0, "RRF fields should be set");
            assertTrue(doc.getCrossScore() > 0, "cross_score should be set");
            assertTrue(doc.getCrossRank() > 0, "cross_rank should be set");
        }
    }

    private List<List<SearchResult>> buildMultiRouteResults() {
        List<List<SearchResult>> resultLists = new ArrayList<>();

        // R1: raw_bm25 (5 results)
        List<SearchResult> r1 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            r1.add(makeResult("md5_r1_" + i, i + 1, 0.9 - i * 0.1, "raw_bm25", i + 1));
        }
        resultLists.add(r1);

        // R2: raw_vector (5 results, some overlap)
        List<SearchResult> r2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            r2.add(makeResult("md5_r2_" + i, i + 1, 0.88 - i * 0.1, "raw_vector", i + 1));
        }
        resultLists.add(r2);

        // R3: rewrite_vector (3 results)
        List<SearchResult> r3 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            r3.add(makeResult("md5_r3_" + i, i + 1, 0.85 - i * 0.1, "rewrite_vector", i + 1));
        }
        resultLists.add(r3);

        // R4: hyde_vector (3 results)
        List<SearchResult> r4 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            r4.add(makeResult("md5_r4_" + i, i + 1, 0.82 - i * 0.1, "hyde_vector", i + 1));
        }
        resultLists.add(r4);

        return resultLists;
    }

    private SearchResult makeResult(String fileMd5, int chunkId, double score, String source, int rank) {
        SearchResult r = new SearchResult(fileMd5, chunkId, "content of " + fileMd5, score);
        r.setRetrievalSource(source);
        r.setRank(rank);
        return r;
    }
}
