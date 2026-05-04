package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 候选过滤编排器。
 *
 * 链路：
 *   多路召回结果
 *     → Deduplication（chunk_id + content hash 去重）
 *     → RRF 融合（Reciprocal Rank Fusion）
 *     → RRF Top-N 截断
 *     → Cross-Encoder 精排
 *     → 按 cross_score 降序排序
 *     → MMR Gate 判断
 *     → MMR 多样性过滤 / 直接 Top-k 截断
 *     → Final Top-k Chunks
 */
@Service
public class CandidateFilterPipeline {

    private static final Logger logger = LoggerFactory.getLogger(CandidateFilterPipeline.class);

    private final DeduplicationService deduplicationService;
    private final RrfFusionService rrfFusionService;
    private final CrossEncoderReranker crossEncoderReranker;
    private final MmrGate mmrGate;
    private final MmrFilter mmrFilter;
    private final RagProperties ragProperties;

    public CandidateFilterPipeline(DeduplicationService deduplicationService,
                                   RrfFusionService rrfFusionService,
                                   CrossEncoderReranker crossEncoderReranker,
                                   MmrGate mmrGate,
                                   MmrFilter mmrFilter,
                                   RagProperties ragProperties) {
        this.deduplicationService = deduplicationService;
        this.rrfFusionService = rrfFusionService;
        this.crossEncoderReranker = crossEncoderReranker;
        this.mmrGate = mmrGate;
        this.mmrFilter = mmrFilter;
        this.ragProperties = ragProperties;
    }

    /**
     * 执行完整的候选过滤链路。
     *
     * @param rawQuery    用户原始 query（用作 Cross-Encoder 的意图锚点）
     * @param intent      用户问题意图（用于 MMR Gate）
     * @param resultLists 多路召回结果
     * @return FilterResult 包含精排后候选、MMR 决策、最终 top-k docs
     */
    public FilterResult filter(String rawQuery, String intent, List<List<SearchResult>> resultLists) {
        logger.info("Starting candidate filter pipeline. {} route lists, intent: {}", resultLists.size(), intent);
        long startTime = System.currentTimeMillis();

        RagProperties.Rrf rrfCfg = ragProperties.getRrf();

        // Step 1: 去重（展开多路 + chunk_id + content hash 去重）
        List<SearchResult> deduped = deduplicationService.deduplicate(resultLists);
        logger.debug("After dedup: {} candidates", deduped.size());

        // Step 2: RRF 融合
        List<SearchResult> rrfRanked = rrfFusionService.fuse(resultLists, rrfCfg.getK());
        logger.debug("After RRF fusion (k={}): {} candidates", rrfCfg.getK(), rrfRanked.size());

        // Step 3: RRF Top-N 截断（控制进入 Cross-Encoder 的候选数量）
        int topN = Math.min(rrfCfg.getTopN(), rrfRanked.size());
        List<SearchResult> candidates = new ArrayList<>(rrfRanked.subList(0, topN));
        logger.debug("After RRF Top-{} truncation: {} candidates", topN, candidates.size());

        // Note: Dedup 已在 RRF 之前做了，但这里的 candidates 是 RRF 重排后的结果，
        // 它们已经在 RRF 融合时通过 chunk_id 去重，通常不需要再次去重。

        // Step 4: Cross-Encoder 精排
        List<SearchResult> crossRanked = crossEncoderReranker.rerank(rawQuery, candidates);
        logger.debug("After Cross-Encoder: {} ranked docs, top score: {}",
                crossRanked.size(),
                crossRanked.isEmpty() ? "N/A" : String.format("%.3f", crossRanked.get(0).getCrossScore()));

        // Step 5: MMR Gate 判断（Phase 6: 含断崖检测 + 相似度计算）
        int finalTopK = ragProperties.getFinalSelection().getTopK();
        MmrDecision mmrDecision = mmrGate.decide(intent, finalTopK, crossRanked);
        logger.info("MMR decision: useMmr={}, reason={}", mmrDecision.isUseMmr(), mmrDecision.getReason());

        // Step 6: MMR 多样性过滤 或 直接 Top-k
        List<SearchResult> finalDocs;
        if (mmrDecision.isUseMmr()) {
            double lambda = ragProperties.getMmr().getLambda();
            finalDocs = mmrFilter.filter(crossRanked, finalTopK, lambda);
            logger.debug("After MMR (λ={}): {} final docs", lambda, finalDocs.size());
        } else {
            int k = Math.min(finalTopK, crossRanked.size());
            finalDocs = new ArrayList<>(crossRanked.subList(0, k));
            for (int i = 0; i < finalDocs.size(); i++) {
                finalDocs.get(i).setFinalRank(i + 1);
            }
            logger.debug("Direct top-{}: {} final docs", finalTopK, finalDocs.size());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Candidate filter pipeline completed in {}ms. Final: {} chunks", elapsed, finalDocs.size());

        return new FilterResult(crossRanked, mmrDecision, finalDocs);
    }

    /**
     * 候选过滤结果。
     */
    public record FilterResult(
            List<SearchResult> crossRankedDocs,
            MmrDecision mmrDecision,
            List<SearchResult> finalDocs) {
    }
}
