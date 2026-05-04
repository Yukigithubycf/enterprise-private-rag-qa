package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.MmrDecision;
import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
import com.yuki.enterprise_private_rag_qa.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 总编排器（Phase 5）。
 * 串联 Stage 1→2→3→4，输出最终检索结果和完整链路追踪信息。
 *
 * 链路：
 *   raw_query → QueryPipeline (Stage 1)
 *            → MultiRouteRetriever (Stage 2)
 *            → CandidateFilterPipeline (Stage 3)
 *            → ReflectionPipeline (Stage 4)
 *            → Final Context
 *
 * Fallback: 任一阶段异常时降级为旧版 2 路召回（raw BM25 + raw Vector） + RRF 逻辑。
 */
@Service
public class RagPipeline {

    private static final Logger logger = LoggerFactory.getLogger(RagPipeline.class);

    private final QueryPipeline queryPipeline;
    private final MultiRouteRetriever multiRouteRetriever;
    private final CandidateFilterPipeline candidateFilterPipeline;
    private final ReflectionPipeline reflectionPipeline;
    private final ParentAggregator parentAggregator;
    private final HybridSearchService hybridSearchService;
    private final RagProperties ragProperties;

    public RagPipeline(QueryPipeline queryPipeline,
                       MultiRouteRetriever multiRouteRetriever,
                       CandidateFilterPipeline candidateFilterPipeline,
                       ReflectionPipeline reflectionPipeline,
                       ParentAggregator parentAggregator,
                       HybridSearchService hybridSearchService,
                       RagProperties ragProperties) {
        this.queryPipeline = queryPipeline;
        this.multiRouteRetriever = multiRouteRetriever;
        this.candidateFilterPipeline = candidateFilterPipeline;
        this.reflectionPipeline = reflectionPipeline;
        this.parentAggregator = parentAggregator;
        this.hybridSearchService = hybridSearchService;
        this.ragProperties = ragProperties;
    }

    /**
     * 执行完整的 RAG 检索链路。
     *
     * @param rawQuery    用户原始问题
     * @param userId      用户 ID（用于权限过滤）
     * @param chatHistory 格式化的对话历史（可为 null）
     * @return RagResult 包含最终 docs 和全链路追踪信息
     */
    public RagResult execute(String rawQuery, String userId, String chatHistory) {
        logger.info("===== RAG Pipeline START =====");
        logger.info("rawQuery: {}, userId: {}", rawQuery, userId);
        long pipelineStart = System.currentTimeMillis();

        try {
            // Stage 1: Query 理解
            long t1 = System.currentTimeMillis();
            QueryInfo queryInfo = queryPipeline.process(rawQuery, chatHistory);
            logger.info("[Stage 1] Query Understanding completed in {}ms. intent={}, entities={}, rewritten={}, hyde={}",
                    System.currentTimeMillis() - t1,
                    queryInfo.getIntent(),
                    queryInfo.getEntities().size(),
                    queryInfo.getRewrittenQueries().size(),
                    queryInfo.getHydeDocument() != null);

            // Stage 2: 多路召回
            long t2 = System.currentTimeMillis();
            List<List<SearchResult>> resultLists = multiRouteRetriever.retrieve(queryInfo, userId);
            logger.info("[Stage 2] Multi-Route Retrieval completed in {}ms. {} routes returned",
                    System.currentTimeMillis() - t2, resultLists.size());

            // Stage 3: 候选过滤
            long t3 = System.currentTimeMillis();
            CandidateFilterPipeline.FilterResult filterResult = candidateFilterPipeline.filter(
                    rawQuery, queryInfo.getIntent(), resultLists);
            logger.info("[Stage 3] Candidate Filtering completed in {}ms. crossRanked={}, final={}",
                    System.currentTimeMillis() - t3,
                    filterResult.crossRankedDocs().size(),
                    filterResult.finalDocs().size());

            // Stage 4: 反思与补充检索
            long t4 = System.currentTimeMillis();
            Query permissionFilter = hybridSearchService.buildPermissionFilter(userId);
            ReflectionPipeline.ReflectionPipelineResult reflectionResult = reflectionPipeline.reflect(
                    rawQuery,
                    queryInfo.getIntent(),
                    queryInfo.getMainRewrittenQuery(),
                    resultLists,
                    filterResult,
                    permissionFilter,
                    chatHistory);
            logger.info("[Stage 4] Reflection completed in {}ms. usedSupplementary={}, final={}",
                    System.currentTimeMillis() - t4,
                    reflectionResult.usedSupplementaryRetrieval(),
                    reflectionResult.finalDocs().size());

            // Phase 6.4: 父块聚合 —— child chunks → parent blocks
            List<SearchResult> finalDocs = reflectionResult.finalDocs();
            if (ragProperties.getFinalSelection().isEnableParentAggregation()) {
                long tAgg = System.currentTimeMillis();
                int parentTopK = Math.min(ragProperties.getFinalSelection().getTopK(), finalDocs.size());
                finalDocs = parentAggregator.aggregate(finalDocs, parentTopK);
                logger.info("[Phase 6.4] Parent aggregation completed in {}ms. {} child → {} parents",
                        System.currentTimeMillis() - tAgg, reflectionResult.finalDocs().size(), finalDocs.size());
            }

            long totalElapsed = System.currentTimeMillis() - pipelineStart;
            logger.info("===== RAG Pipeline COMPLETE in {}ms =====", totalElapsed);

            return new RagResult(
                    finalDocs,
                    queryInfo,
                    filterResult.crossRankedDocs(),
                    filterResult.mmrDecision(),
                    reflectionResult.reflectionResult(),
                    reflectionResult.usedSupplementaryRetrieval(),
                    reflectionResult.supplementaryQueries(),
                    false
            );

        } catch (Exception e) {
            logger.error("RAG Pipeline failed: {}. Falling back to legacy 2-route search.", e.getMessage(), e);
            return fallbackToLegacy(rawQuery, userId);
        }
    }

    /**
     * 降级为旧版 2 路召回逻辑。
     */
    private RagResult fallbackToLegacy(String rawQuery, String userId) {
        try {
            List<SearchResult> legacyResults = hybridSearchService.searchWithPermission(rawQuery, userId,
                    ragProperties.getFinalSelection().getTopK());
            // Assign final ranks
            for (int i = 0; i < legacyResults.size(); i++) {
                legacyResults.get(i).setFinalRank(i + 1);
            }
            logger.info("Fallback legacy search returned {} results", legacyResults.size());
            return new RagResult(legacyResults, null, null, null, null, false, List.of(), true);
        } catch (Exception ex) {
            logger.error("Even legacy fallback failed: {}", ex.getMessage(), ex);
            return new RagResult(List.of(), null, null, null, null, false, List.of(), true);
        }
    }

    /**
     * RAG 完整链路结果。
     */
    public record RagResult(
            /** 最终选出的 top-k chunks */
            List<SearchResult> finalDocs,
            /** Stage 1 输出的完整 query 信息 */
            QueryInfo queryInfo,
            /** Stage 3 Cross-Encoder 精排后的全量候选 */
            List<SearchResult> crossRankedDocs,
            /** Stage 3 MMR 门控决策 */
            MmrDecision mmrDecision,
            /** Stage 4 Reflection 检查结果（null 表示未执行） */
            ReflectionResult reflectionResult,
            /** 是否使用了补充检索 */
            boolean usedSupplementaryRetrieval,
            /** 补充检索 query 列表 */
            List<String> supplementaryQueries,
            /** 是否使用了 fallback 降级逻辑 */
            boolean isFallback) {
    }
}
