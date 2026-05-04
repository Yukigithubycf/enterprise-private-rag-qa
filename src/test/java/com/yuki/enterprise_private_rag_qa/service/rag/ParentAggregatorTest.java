package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParentAggregatorTest {

    private ParentAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ParentAggregator();
    }

    @Test
    void shouldReturnEmptyListForNullInput() {
        assertTrue(aggregator.aggregate(null, 5).isEmpty());
    }

    @Test
    void shouldReturnEmptyListForEmptyInput() {
        assertTrue(aggregator.aggregate(List.of(), 5).isEmpty());
    }

    @Test
    void shouldGroupChildrenByParentKey() {
        // Two children from same parent (same fileMd5 + parentId)
        SearchResult child1 = childResult("md5_A", 0, "parent_1", "child content 1",
                "full parent text", 0.9, 1);
        SearchResult child2 = childResult("md5_A", 1, "parent_1", "child content 2",
                null, 0.8, 2);
        // One child from different parent
        SearchResult child3 = childResult("md5_A", 2, "parent_2", "child content 3",
                "other parent text", 0.7, 3);

        List<SearchResult> result = aggregator.aggregate(List.of(child1, child2, child3), 10);

        assertEquals(2, result.size());
        // Best-ranked child per parent group becomes the parent result
        assertEquals("parent_1", result.get(0).getParentId());
        assertEquals("parent_2", result.get(1).getParentId());
    }

    @Test
    void shouldUseBestRankedChildPerParent() {
        // child1 rank=3, child2 rank=1 (better) — same parent
        SearchResult child1 = childResult("md5_A", 0, "parent_1", "worse", "full A", 0.7, 3);
        SearchResult child2 = childResult("md5_A", 1, "parent_1", "better", null, 0.9, 1);

        List<SearchResult> result = aggregator.aggregate(List.of(child1, child2), 10);

        assertEquals(1, result.size());
        // parentTextContent filled from sibling even when best-ranked child lacks it
        assertEquals("full A", result.get(0).getTextContent());
        assertEquals("full A", result.get(0).getParentTextContent());
    }

    @Test
    void shouldFillMissingParentTextFromSibling() {
        // Better-ranked child missing parentTextContent, sibling has it
        SearchResult child1 = childResult("md5_A", 0, "parent_1", "better rank", null, 0.9, 1);
        SearchResult child2 = childResult("md5_A", 1, "parent_1", "worse rank", "full parent text", 0.8, 3);

        List<SearchResult> result = aggregator.aggregate(List.of(child1, child2), 10);

        assertEquals(1, result.size());
        assertEquals("full parent text", result.get(0).getTextContent());
        assertEquals("full parent text", result.get(0).getParentTextContent());
    }

    @Test
    void shouldUseChunkIdAsParentKeyWhenNoParentId() {
        // No parentId set → parent key = fileMd5#chunkId
        SearchResult child1 = childResult("md5_A", 5, null, "content A", null, 0.8, 1);
        SearchResult child2 = childResult("md5_A", 10, null, "content B", null, 0.7, 2);

        List<SearchResult> result = aggregator.aggregate(List.of(child1, child2), 10);

        // Different chunkIds → different parents
        assertEquals(2, result.size());
    }

    @Test
    void shouldTruncateToTopK() {
        List<SearchResult> children = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // Each child has unique parent (unique parentId)
            children.add(childResult("md5_A", i, "parent_" + i, "content " + i,
                    "parent text " + i, 0.9 - i * 0.05, i + 1));
        }

        List<SearchResult> result = aggregator.aggregate(children, 3);

        assertEquals(3, result.size());
    }

    @Test
    void shouldReassignFinalRanks() {
        List<SearchResult> children = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            children.add(childResult("md5_A", i, "parent_" + i, "content " + i,
                    "parent text " + i, 0.9 - i * 0.05, i + 1));
        }

        List<SearchResult> result = aggregator.aggregate(children, 10);

        assertEquals(5, result.size());
        assertEquals(1, result.get(0).getFinalRank());
        assertEquals(2, result.get(1).getFinalRank());
        assertEquals(5, result.get(4).getFinalRank());
    }

    @Test
    void shouldPreferParentTextContentOverChildTextContent() {
        SearchResult child = childResult("md5_A", 0, "parent_1", "short child chunk",
                "much longer parent text with full context", 0.9, 1);

        List<SearchResult> result = aggregator.aggregate(List.of(child), 10);

        assertEquals(1, result.size());
        assertEquals("much longer parent text with full context", result.get(0).getTextContent());
    }

    @Test
    void shouldFallbackToChildTextWhenNoParentText() {
        SearchResult child = childResult("md5_A", 0, "parent_1", "only child text",
                null, 0.9, 1);

        List<SearchResult> result = aggregator.aggregate(List.of(child), 10);

        assertEquals(1, result.size());
        assertEquals("only child text", result.get(0).getTextContent());
    }

    private SearchResult childResult(String fileMd5, int chunkId, String parentId,
                                     String textContent, String parentTextContent,
                                     double score, int finalRank) {
        SearchResult r = new SearchResult(fileMd5, chunkId, parentId, textContent,
                parentTextContent, score, null, null, false, null);
        r.setFinalRank(finalRank);
        r.setRetrievalSource("test_source");
        return r;
    }
}
