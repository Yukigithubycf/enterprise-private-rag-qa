package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RrfFusionServiceTest {

    private final RrfFusionService service = new RrfFusionService();

    @Test
    void shouldFuseMultipleRoutes() {
        // Route 1: raw_bm25
        List<SearchResult> route1 = new ArrayList<>();
        route1.add(makeResult("md5_1", 1, 0.9, "raw_bm25", 1));
        route1.add(makeResult("md5_2", 2, 0.8, "raw_bm25", 2));
        route1.add(makeResult("md5_3", 3, 0.7, "raw_bm25", 3));

        // Route 2: raw_vector
        List<SearchResult> route2 = new ArrayList<>();
        route2.add(makeResult("md5_2", 2, 0.85, "raw_vector", 1)); // overlap: rank 2 in r1
        route2.add(makeResult("md5_1", 1, 0.88, "raw_vector", 2)); // overlap: rank 1 in r1
        route2.add(makeResult("md5_4", 4, 0.6, "raw_vector", 3));

        List<List<SearchResult>> resultLists = List.of(route1, route2);
        List<SearchResult> fused = service.fuse(resultLists, 60);

        assertEquals(4, fused.size());
        // md5_1 appears in both routes at rank 1 and 2 → higher RRF score
        assertNotNull(fused.get(0).getRrfScore());
        assertTrue(fused.get(0).getRrfScore() > 0);
        assertNotNull(fused.get(0).getRetrievalSources());
        assertTrue(fused.get(0).getRetrievalSources().size() >= 1);
    }

    @Test
    void shouldAssignRrfRank() {
        List<SearchResult> route1 = List.of(makeResult("md5_A", 1, 0.9, "raw_bm25", 1));
        List<SearchResult> route2 = List.of(makeResult("md5_B", 2, 0.8, "raw_vector", 1));

        List<SearchResult> fused = service.fuse(List.of(route1, route2), 60);

        assertEquals(2, fused.size());
        assertEquals(1, fused.get(0).getRrfRank());
        assertEquals(2, fused.get(1).getRrfRank());
    }

    @Test
    void shouldHandleEmptyInput() {
        assertTrue(service.fuse(null).isEmpty());
        assertTrue(service.fuse(List.of()).isEmpty());
    }

    @Test
    void shouldHandleOneRoute() {
        List<SearchResult> route = List.of(makeResult("md5_1", 1, 0.9, "raw_bm25", 1));
        List<SearchResult> fused = service.fuse(List.of(route), 60);

        assertEquals(1, fused.size());
        assertEquals("raw_bm25", fused.get(0).getRetrievalSources().get(0));
    }

    @Test
    void shouldBoostMultiHitDocs() {
        // Chunk appears in multiple routes → higher RRF score
        List<SearchResult> route1 = List.of(makeResult("md5_common", 1, 0.9, "raw_bm25", 1));
        List<SearchResult> route2 = List.of(makeResult("md5_common", 1, 0.85, "raw_vector", 1));
        List<SearchResult> route3 = List.of(makeResult("md5_other", 2, 0.8, "rewrite_vector", 1));

        List<SearchResult> fused = service.fuse(List.of(route1, route2, route3), 60);

        // The common doc should have highest RRF score (hits in 2 routes)
        assertEquals("md5_common", fused.get(0).getFileMd5());
        assertTrue(fused.get(0).getRetrievalSources().size() >= 2);
    }

    private SearchResult makeResult(String fileMd5, int chunkId, double score, String source, int rank) {
        SearchResult r = new SearchResult(fileMd5, chunkId, "content of " + fileMd5, score);
        r.setRetrievalSource(source);
        r.setRank(rank);
        return r;
    }
}
