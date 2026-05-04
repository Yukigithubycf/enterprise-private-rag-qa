package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MMR（Maximal Marginal Relevance）多样性过滤。
 * 在高相关候选中减少语义重复，让最终 top-k context 覆盖更多不同信息点。
 *
 * 公式：MMR(d) = λ × cross_score(d) - (1-λ) × max cosine_similarity(d, selected_docs)
 */
@Component
public class MmrFilter {

    private static final Logger logger = LoggerFactory.getLogger(MmrFilter.class);

    private final EmbeddingClient embeddingClient;

    public MmrFilter(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /**
     * 执行 MMR 多样性过滤。
     *
     * @param docs         Cross-Encoder 排序后的候选列表（含 cross_score）
     * @param topK         最终要选取的 chunk 数量
     * @param lambdaMult   相关性 vs 多样性权重，默认 0.7
     * @return MMR 筛选后的 top-K 结果（含 mmr_score, mmr_rank, final_rank）
     */
    public List<SearchResult> filter(List<SearchResult> docs, int topK, double lambdaMult) {
        if (docs == null || docs.isEmpty() || topK <= 0) {
            return List.of();
        }

        if (docs.size() <= topK) {
            // 候选数量不足 topK，全返回
            for (int i = 0; i < docs.size(); i++) {
                docs.get(i).setMmrScore(docs.get(i).getCrossScore());
                docs.get(i).setFinalRank(i + 1);
            }
            return new ArrayList<>(docs);
        }

        // 1. 为每个候选生成 embedding（用于计算相似度）
        List<float[]> embeddings = generateEmbeddings(docs);
        if (embeddings == null || embeddings.size() != docs.size()) {
            logger.warn("Embedding generation for MMR failed, falling back to direct top-K");
            return fallbackTopK(docs, topK);
        }

        // 2. MMR 贪心选择
        List<SearchResult> selected = new ArrayList<>();
        List<float[]> selectedEmbeddings = new ArrayList<>();
        boolean[] chosen = new boolean[docs.size()];

        for (int round = 0; round < topK && round < docs.size(); round++) {
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;

            for (int i = 0; i < docs.size(); i++) {
                if (chosen[i]) continue;

                double relevance = docs.get(i).getCrossScore();

                double diversityPenalty = 0.0;
                if (!selected.isEmpty()) {
                    final int idx = i; // capture for lambda
                    diversityPenalty = selectedEmbeddings.stream()
                            .mapToDouble(emb -> cosineSimilarity(embeddings.get(idx), emb))
                            .max()
                            .orElse(0.0);
                }

                double mmrScore = lambdaMult * relevance - (1.0 - lambdaMult) * diversityPenalty;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = i;
                }
            }

            if (bestIdx < 0) break;

            chosen[bestIdx] = true;
            SearchResult selectedDoc = docs.get(bestIdx);
            selectedDoc.setMmrScore(bestScore);
            selected.add(selectedDoc);
            selectedEmbeddings.add(embeddings.get(bestIdx));
        }

        // 3. 分配排名
        for (int i = 0; i < selected.size(); i++) {
            selected.get(i).setFinalRank(i + 1);
        }

        logger.debug("MMR filter: {} candidates → {} selected (λ={})", docs.size(), selected.size(), lambdaMult);
        return selected;
    }

    private List<float[]> generateEmbeddings(List<SearchResult> docs) {
        try {
            List<String> texts = docs.stream()
                    .map(d -> d.getTextContent() != null ? d.getTextContent() : "")
                    .toList();
            return embeddingClient.embed(texts);
        } catch (Exception e) {
            logger.error("MMR embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    private List<SearchResult> fallbackTopK(List<SearchResult> docs, int topK) {
        List<SearchResult> result = new ArrayList<>(docs.subList(0, Math.min(topK, docs.size())));
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setFinalRank(i + 1);
            result.get(i).setMmrScore(result.get(i).getCrossScore());
        }
        return result;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
