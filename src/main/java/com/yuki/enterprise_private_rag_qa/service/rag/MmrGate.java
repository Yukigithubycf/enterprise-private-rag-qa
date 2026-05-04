package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.Intent;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MMR 门控判断（Phase 6 升级：意图规则 + 分数断崖检测 + 候选相似度计算）。
 */
@Component
public class MmrGate {

    private static final Logger logger = LoggerFactory.getLogger(MmrGate.class);

    private final RagProperties ragProperties;
    private final EmbeddingClient embeddingClient;

    public MmrGate(RagProperties ragProperties, EmbeddingClient embeddingClient) {
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 仅基于意图规则判断（Phase 3 兼容接口）。
     */
    public MmrDecision decide(String intent, int finalTopK) {
        return decide(intent, finalTopK, null);
    }

    /**
     * 综合判断是否启用 MMR：意图规则 + 分数断崖 + 候选相似度。
     *
     * @param intent          用户问题意图
     * @param finalTopK       最终要返回的 chunk 数量
     * @param crossRankedDocs Cross-Encoder 精排后的候选列表（用于断崖检测和相似度计算，可为 null）
     * @return MMR 门控决策
     */
    public MmrDecision decide(String intent, int finalTopK, List<SearchResult> crossRankedDocs) {
        // 规则 1: final_top_k <= 2 时关闭 MMR
        if (finalTopK <= 2) {
            logger.debug("MMR disabled: final_top_k <= 2");
            return MmrDecision.disable("final_top_k <= 2，多样性过滤收益较低");
        }

        // 规则 2: 精确事实/定位型问题关闭 MMR
        if (Intent.isPrecisionIntent(intent)) {
            logger.debug("MMR disabled: intent={} is precision type", intent);
            return MmrDecision.disable("intent=" + intent + "，属于精确定位类问题");
        }

        // Phase 6.2: 分数断崖检测 —— 当 top-1 明显优于 top-2 时跳过 MMR
        if (crossRankedDocs != null && crossRankedDocs.size() >= 2) {
            MmrDecision cliffDecision = checkScoreCliff(crossRankedDocs);
            if (cliffDecision != null) {
                return cliffDecision;
            }
        }

        // 规则 3: 多角度型问题开启 MMR
        if (Intent.isMultiAngleIntent(intent)) {
            logger.debug("MMR enabled: intent={} needs multi-angle context", intent);
            return MmrDecision.enable("intent=" + intent + "，需要多角度 context");
        }

        // Phase 6.3: 意图不明确时，计算候选相似度辅助判断
        if (crossRankedDocs != null && !crossRankedDocs.isEmpty()) {
            return decideBySimilarity(intent, crossRankedDocs);
        }

        // 兜底：意图不明确且无候选数据 → 默认关闭
        logger.debug("MMR disabled: intent={} is ambiguous, defaulting to off", intent);
        return MmrDecision.disable("intent 不明确，默认关闭 MMR");
    }

    /**
     * Phase 6.2: 检查 Cross-Encoder 分数是否出现断崖。
     * 如果 top-1 的 cross_score 明显高于 top-2（差距超过 cliffThreshold），
     * 说明第一个结果已经非常相关，MMR 多样性过滤可能引入噪声。
     */
    private MmrDecision checkScoreCliff(List<SearchResult> crossRankedDocs) {
        double cliffThreshold = ragProperties.getMmr().getCliffThreshold();
        double top1Score = crossRankedDocs.get(0).getCrossScore();
        double top2Score = crossRankedDocs.get(1).getCrossScore();
        double gap = top1Score - top2Score;

        if (gap >= cliffThreshold) {
            logger.info("MMR disabled by cliff detection: top1={:.3f}, top2={:.3f}, gap={:.3f} >= threshold={}",
                    top1Score, top2Score, gap, cliffThreshold);
            return MmrDecision.disable(String.format(
                    "Cross-Encoder 分数断崖: top1=%.3f, top2=%.3f, gap=%.3f >= %.2f",
                    top1Score, top2Score, gap, cliffThreshold));
        }
        return null;
    }

    /**
     * Phase 6.3: 基于候选相似度判断是否启用 MMR。
     * 当意图不明确时，计算 Cross-Encoder top-N 候选的平均两两语义相似度。
     * 如果相似度较高（>= threshold），说明候选重复严重，需要 MMR 去重。
     */
    private MmrDecision decideBySimilarity(String intent, List<SearchResult> crossRankedDocs) {
        double similarityThreshold = ragProperties.getMmr().getSimilarityThreshold();
        int checkTopN = Math.min(ragProperties.getMmr().getCheckTopN(), crossRankedDocs.size());

        if (checkTopN <= 1) {
            return MmrDecision.disable("候选数量不足，无法计算相似度");
        }

        List<SearchResult> topN = crossRankedDocs.subList(0, checkTopN);

        try {
            List<float[]> embeddings = embeddingClient.embed(
                    topN.stream()
                            .map(d -> d.getTextContent() != null ? d.getTextContent() : "")
                            .toList());

            if (embeddings == null || embeddings.size() < 2) {
                logger.warn("MMR similarity check: embedding generation failed");
                return MmrDecision.disable("embedding 生成失败，默认关闭 MMR");
            }

            double avgSimilarity = computeAveragePairwiseSimilarity(embeddings);
            logger.info("MMR similarity check: top-{} avgCosineSimilarity={:.3f}, threshold={}",
                    checkTopN, avgSimilarity, similarityThreshold);

            if (avgSimilarity >= similarityThreshold) {
                return MmrDecision.enable(String.format(
                        "候选相似度过高 (avgCosine=%.3f >= %.2f)，需要 MMR 去重",
                        avgSimilarity, similarityThreshold));
            } else {
                return MmrDecision.disable(String.format(
                        "候选相似度较低 (avgCosine=%.3f < %.2f)，不需要 MMR 去重",
                        avgSimilarity, similarityThreshold));
            }
        } catch (Exception e) {
            logger.error("MMR similarity calculation failed: {}", e.getMessage());
            return MmrDecision.disable("相似度计算出错，默认关闭 MMR");
        }
    }

    /**
     * 计算 embedding 列表的平均两两余弦相似度。
     */
    private static double computeAveragePairwiseSimilarity(List<float[]> embeddings) {
        int n = embeddings.size();
        double totalSimilarity = 0.0;
        int pairCount = 0;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                totalSimilarity += cosineSimilarity(embeddings.get(i), embeddings.get(j));
                pairCount++;
            }
        }

        return pairCount > 0 ? totalSimilarity / pairCount : 0.0;
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
