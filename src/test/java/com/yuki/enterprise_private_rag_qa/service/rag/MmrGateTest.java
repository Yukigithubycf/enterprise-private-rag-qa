package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
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
class MmrGateTest {

    @Mock
    private EmbeddingClient embeddingClient;

    private MmrGate gate;

    @BeforeEach
    void setUp() {
        gate = new MmrGate(new RagProperties(), embeddingClient);
    }

    // === Phase 3 基础规则测试 ===

    @Test
    void shouldDisableMmrWhenTopKSmall() {
        MmrDecision d = gate.decide("原因分析", 2);
        assertFalse(d.isUseMmr());
        assertTrue(d.getReason().contains("<= 2"));
    }

    @Test
    void shouldDisableMmrForPrecisionIntent() {
        MmrDecision d = gate.decide("代码定位", 8);
        assertFalse(d.isUseMmr());
        assertTrue(d.getReason().contains("精确定位"));
    }

    @Test
    void shouldEnableMmrForMultiAngleIntent() {
        MmrDecision d = gate.decide("实现方法", 8);
        assertTrue(d.isUseMmr());
    }

    @Test
    void shouldEnableMmrForReasonAnalysis() {
        MmrDecision d = gate.decide("原因分析", 6);
        assertTrue(d.isUseMmr());
    }

    @Test
    void shouldEnableMmrForComparison() {
        MmrDecision d = gate.decide("对比分析", 8);
        assertTrue(d.isUseMmr());
    }

    @Test
    void shouldDisableMmrForAmbiguousIntent() {
        MmrDecision d = gate.decide("通用问答", 8);
        assertFalse(d.isUseMmr());
    }

    // === Phase 6.2: 分数断崖检测 ===

    @Test
    void shouldDisableMmrOnScoreCliff() {
        List<SearchResult> crossRanked = new ArrayList<>();
        SearchResult r1 = new SearchResult("md5_1", 1, "top content", 0.95);
        r1.setCrossScore(0.95);
        SearchResult r2 = new SearchResult("md5_2", 2, "second content", 0.55);
        r2.setCrossScore(0.55);
        crossRanked.add(r1);
        crossRanked.add(r2);

        // gap=0.40 > cliffThreshold=0.3 → should disable MMR
        MmrDecision d = gate.decide("实现方法", 8, crossRanked);
        assertFalse(d.isUseMmr());
        assertTrue(d.getReason().contains("断崖"));
    }

    @Test
    void shouldNotDisableMmrOnSmallGap() {
        List<SearchResult> crossRanked = new ArrayList<>();
        SearchResult r1 = new SearchResult("md5_1", 1, "top content", 0.88);
        r1.setCrossScore(0.88);
        SearchResult r2 = new SearchResult("md5_2", 2, "second content", 0.75);
        r2.setCrossScore(0.75);
        crossRanked.add(r1);
        crossRanked.add(r2);

        // gap=0.13 < cliffThreshold=0.3 → should still enable MMR for multi-angle intent
        MmrDecision d = gate.decide("实现方法", 8, crossRanked);
        assertTrue(d.isUseMmr());
    }

    @Test
    void shouldNotCheckCliffWithLessThanTwoDocs() {
        List<SearchResult> crossRanked = new ArrayList<>();
        SearchResult r1 = new SearchResult("md5_1", 1, "single", 0.9);
        r1.setCrossScore(0.9);
        crossRanked.add(r1);

        // Single doc should fall through to intent rules
        MmrDecision d = gate.decide("实现方法", 8, crossRanked);
        assertTrue(d.isUseMmr());
    }

    // === Phase 6.3: 候选相似度判断 ===

    @Test
    void shouldEnableMmrOnHighSimilarity() {
        when(embeddingClient.embed(anyList()))
                .thenAnswer(inv -> {
                    List<String> texts = inv.getArgument(0);
                    List<float[]> embeddings = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) {
                        // Same vector for all → avgCosine=1.0
                        embeddings.add(new float[]{0.5f, 0.5f, 0.5f, 0.5f});
                    }
                    return embeddings;
                });

        List<SearchResult> crossRanked = buildCrossRankedDocs(5);

        // Ambiguous intent + high similarity → enable MMR
        MmrDecision d = gate.decide("通用问答", 8, crossRanked);
        assertTrue(d.isUseMmr());
        assertTrue(d.getReason().contains("相似度过高"));
    }

    @Test
    void shouldDisableMmrOnLowSimilarity() {
        when(embeddingClient.embed(anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> texts = inv.getArgument(0);
                    List<float[]> embeddings = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) {
                        // Orthogonal vectors → avgCosine≈0
                        float[] emb = new float[4];
                        emb[i % 4] = 1.0f;
                        embeddings.add(emb);
                    }
                    return embeddings;
                });

        List<SearchResult> crossRanked = buildCrossRankedDocs(5);

        // Ambiguous intent + low similarity → disable MMR
        MmrDecision d = gate.decide("通用问答", 8, crossRanked);
        assertFalse(d.isUseMmr());
        assertTrue(d.getReason().contains("相似度较低"));
    }

    @Test
    void shouldFallbackOnEmbeddingFailure() {
        when(embeddingClient.embed(anyList()))
                .thenThrow(new RuntimeException("Embedding API down"));

        List<SearchResult> crossRanked = buildCrossRankedDocs(5);

        MmrDecision d = gate.decide("通用问答", 8, crossRanked);
        assertFalse(d.isUseMmr());
        assertTrue(d.getReason().contains("embedding 生成失败") || d.getReason().contains("计算出错"));
    }

    private List<SearchResult> buildCrossRankedDocs(int count) {
        List<SearchResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult r = new SearchResult("md5_" + i, i, "content " + i, 0.9 - i * 0.05);
            r.setCrossScore(0.9 - i * 0.05);
            list.add(r);
        }
        return list;
    }
}
