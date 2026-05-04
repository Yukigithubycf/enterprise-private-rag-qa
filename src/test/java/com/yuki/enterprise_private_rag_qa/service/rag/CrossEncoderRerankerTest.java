package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrossEncoderRerankerTest {

    private CrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        // No apiUrl configured → stub mode
        reranker = new CrossEncoderReranker(ragProperties, new ObjectMapper());
    }

    @Test
    void shouldReturnEmptyListForNullInput() {
        assertTrue(reranker.rerank("query", null).isEmpty());
    }

    @Test
    void shouldReturnEmptyListForEmptyInput() {
        assertTrue(reranker.rerank("query", List.of()).isEmpty());
    }

    @Test
    void shouldNormalizeScoresInStubMode() {
        List<SearchResult> candidates = new ArrayList<>();
        candidates.add(result("doc1", 0.95, 1));
        candidates.add(result("doc2", 0.80, 2));
        candidates.add(result("doc3", 0.60, 3));

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        assertEquals(3, ranked.size());
        // CrossScore should be normalized: score / maxScore
        assertEquals(1.0, ranked.get(0).getCrossScore(), 0.001);
        assertEquals(0.80 / 0.95, ranked.get(1).getCrossScore(), 0.001);
        assertEquals(0.60 / 0.95, ranked.get(2).getCrossScore(), 0.001);
    }

    @Test
    void shouldSortDescendingByCrossScore() {
        List<SearchResult> candidates = new ArrayList<>();
        candidates.add(result("doc_low", 0.3, 3));
        candidates.add(result("doc_high", 0.9, 1));
        candidates.add(result("doc_mid", 0.6, 2));

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        assertEquals(0.9, ranked.get(0).getScore(), 0.001);
        assertEquals(0.6, ranked.get(1).getScore(), 0.001);
        assertEquals(0.3, ranked.get(2).getScore(), 0.001);
    }

    @Test
    void shouldAssignCrossRanks() {
        List<SearchResult> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(result("doc" + i, 0.9 - i * 0.1, i + 1));
        }

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        for (int i = 0; i < ranked.size(); i++) {
            assertEquals(i + 1, ranked.get(i).getCrossRank());
        }
    }

    @Test
    void shouldHandleAllZeroScores() {
        List<SearchResult> candidates = new ArrayList<>();
        candidates.add(result("doc1", 0.0, 1));
        candidates.add(result("doc2", 0.0, 2));

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        assertEquals(2, ranked.size());
        assertEquals(0.0, ranked.get(0).getCrossScore(), 0.001);
        assertEquals(0.0, ranked.get(1).getCrossScore(), 0.001);
    }

    @Test
    void shouldHandleSingleCandidate() {
        List<SearchResult> candidates = List.of(result("doc1", 0.85, 1));

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        assertEquals(1, ranked.size());
        assertEquals(1.0, ranked.get(0).getCrossScore(), 0.001);
        assertEquals(1, ranked.get(0).getCrossRank());
    }

    @Test
    void shouldHandleNullScoresAsZero() {
        List<SearchResult> candidates = new ArrayList<>();
        SearchResult r1 = new SearchResult("md5_1", 1, "content 1", 0.9);
        r1.setRank(1);
        SearchResult r2 = new SearchResult("md5_2", 2, "content 2", null);
        r2.setRank(2);

        candidates.add(r1);
        candidates.add(r2);

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        assertEquals(1.0, ranked.get(0).getCrossScore(), 0.001); // 0.9/0.9
        assertEquals(0.0, ranked.get(1).getCrossScore(), 0.001); // 0.0/0.9
    }

    @Test
    void shouldNotUpdateInputListReferences() {
        List<SearchResult> candidates = new ArrayList<>();
        candidates.add(result("doc1", 0.9, 1));

        List<SearchResult> ranked = reranker.rerank("test query", candidates);

        // The returned list should have modified crossScore and crossRank on the same objects
        assertEquals(1.0, ranked.get(0).getCrossScore(), 0.001);
        assertEquals(1, ranked.get(0).getCrossRank());
    }

    private SearchResult result(String content, double score, int rank) {
        SearchResult r = new SearchResult("md5_" + content, rank, content, score);
        r.setRank(rank);
        r.setRetrievalSource("test");
        return r;
    }
}
