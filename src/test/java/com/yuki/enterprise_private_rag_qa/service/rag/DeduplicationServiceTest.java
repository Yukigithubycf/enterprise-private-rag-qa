package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeduplicationServiceTest {

    private final DeduplicationService service = new DeduplicationService();

    @Test
    void shouldDeduplicateById() {
        SearchResult r1 = new SearchResult("md5_A", 1, "same content", 0.9);
        r1.setRetrievalSource("raw_bm25");
        SearchResult r2 = new SearchResult("md5_A", 1, "same content", 0.8); // same id
        r2.setRetrievalSource("raw_vector");

        List<List<SearchResult>> resultLists = List.of(List.of(r1), List.of(r2));
        List<SearchResult> deduped = service.deduplicate(resultLists);

        assertEquals(1, deduped.size());
        assertEquals(0.9, deduped.get(0).getScore()); // keeps higher score
    }

    @Test
    void shouldDeduplicateByContentHash() {
        SearchResult r1 = new SearchResult("md5_A", 1, "identical content here", 0.9);
        SearchResult r2 = new SearchResult("md5_B", 2, "identical content here", 0.7);

        List<List<SearchResult>> resultLists = List.of(List.of(r1), List.of(r2));
        List<SearchResult> deduped = service.deduplicate(resultLists);

        assertEquals(1, deduped.size());
    }

    @Test
    void shouldKeepUniqueDocs() {
        SearchResult r1 = new SearchResult("md5_A", 1, "content one", 0.9);
        SearchResult r2 = new SearchResult("md5_B", 2, "content two", 0.8);

        List<List<SearchResult>> resultLists = List.of(List.of(r1), List.of(r2));
        List<SearchResult> deduped = service.deduplicate(resultLists);

        assertEquals(2, deduped.size());
    }

    @Test
    void shouldHandleEmptyInput() {
        assertTrue(service.deduplicate(null).isEmpty());
        assertTrue(service.deduplicate(List.of()).isEmpty());
    }

    @Test
    void shouldHandleEmptySublists() {
        List<List<SearchResult>> resultLists = new ArrayList<>();
        resultLists.add(List.of());
        resultLists.add(null);
        resultLists.add(List.of(new SearchResult("md5_A", 1, "content A", 0.9)));

        List<SearchResult> deduped = service.deduplicate(resultLists);
        assertEquals(1, deduped.size());
    }
}
