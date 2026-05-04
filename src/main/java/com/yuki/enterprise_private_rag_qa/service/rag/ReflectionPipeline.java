package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.ReflectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 反思与补充层编排器。
 *
 * 链路：
 *   初始 Final Context（来自 CandidateFilterPipeline）
 *     → Reflection Gate 判断是否启用
 *     → ReflectionService 检查 context 充分性
 *     → [如果不足] SupplementaryRetriever 补充检索
 *     → [如果不足] 合并原始结果 + 补充结果 → 重新 CandidateFilterPipeline
 *     → 最终 Top-k Chunks
 *
 * 关键约束：
 *   - 最多补充检索 1 次（不循环）
 *   - 补充检索不重新生成 HyDE 或 Query Rewrite
 *   - 最终答案只使用真实检索 chunks，不使用 Reflection 生成的文本
 */
@Service
public class ReflectionPipeline {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionPipeline.class);

    private final ReflectionGate reflectionGate;
    private final ReflectionService reflectionService;
    private final SupplementaryRetriever supplementaryRetriever;
    private final CandidateFilterPipeline candidateFilterPipeline;
    private final RagProperties ragProperties;

    public ReflectionPipeline(ReflectionGate reflectionGate,
                              ReflectionService reflectionService,
                              SupplementaryRetriever supplementaryRetriever,
                              CandidateFilterPipeline candidateFilterPipeline,
                              RagProperties ragProperties) {
        this.reflectionGate = reflectionGate;
        this.reflectionService = reflectionService;
        this.supplementaryRetriever = supplementaryRetriever;
        this.candidateFilterPipeline = candidateFilterPipeline;
        this.ragProperties = ragProperties;
    }

    /**
     * 执行反思与补充检索流程。
     *
     * @param rawQuery              用户原始问题
     * @param intent                用户问题意图
     * @param mainRewrittenQuery    主改写 query
     * @param originalResultLists   原始多路召回结果（用于补充检索后合并）
     * @param initialFilterResult   CandidateFilterPipeline 的初始过滤结果
     * @param permissionFilter      ES 权限过滤 Query（用于补充检索）
     * @param chatHistory           格式化的对话历史（可为 null）
     * @return ReflectionPipelineResult 包含最终 docs、反思结果、是否使用补充检索
     */
    public ReflectionPipelineResult reflect(String rawQuery,
                                             String intent,
                                             String mainRewrittenQuery,
                                             List<List<SearchResult>> originalResultLists,
                                             CandidateFilterPipeline.FilterResult initialFilterResult,
                                             Query permissionFilter,
                                             String chatHistory) {
        logger.info("Starting reflection pipeline. intent={}, initialFinalDocs={}", intent,
                initialFilterResult.finalDocs() != null ? initialFilterResult.finalDocs().size() : 0);

        int finalTopK = ragProperties.getFinalSelection().getTopK();

        // Step 1: Gate 判断
        if (!reflectionGate.shouldReflect(intent, finalTopK)) {
            logger.info("Reflection gate: disabled → returning initial results directly");
            return new ReflectionPipelineResult(
                    initialFilterResult.finalDocs(),
                    null,
                    false,
                    List.of()
            );
        }

        // Step 2: Reflection 检查
        ReflectionResult reflectionResult = reflectionService.check(
                rawQuery, intent, mainRewrittenQuery, initialFilterResult.finalDocs(), chatHistory);

        // Step 3: Context 足够 → 直接返回
        if (reflectionResult.isSufficient()) {
            logger.info("Reflection: context sufficient (confidence={}) → returning initial results",
                    reflectionResult.getConfidence());
            return new ReflectionPipelineResult(
                    initialFilterResult.finalDocs(),
                    reflectionResult,
                    false,
                    List.of()
            );
        }

        // Step 4: 检查最大反思轮数
        int maxRounds = reflectionGate.getMaxReflectionRounds();
        if (maxRounds <= 0) {
            logger.info("Reflection: max rounds={} → skipping supplementary retrieval", maxRounds);
            return new ReflectionPipelineResult(
                    initialFilterResult.finalDocs(),
                    reflectionResult,
                    false,
                    List.of()
            );
        }

        // Step 5: 获取补充检索 query（最多 3 个）
        List<String> supplementaryQueries = reflectionResult.getSupplementaryQueries();
        if (supplementaryQueries == null || supplementaryQueries.isEmpty()) {
            logger.info("Reflection: no supplementary queries generated → returning initial results");
            return new ReflectionPipelineResult(
                    initialFilterResult.finalDocs(),
                    reflectionResult,
                    false,
                    List.of()
            );
        }

        List<String> queries = supplementaryQueries.size() > 3
                ? supplementaryQueries.subList(0, 3)
                : supplementaryQueries;
        logger.info("Reflection: context insufficient → supplementary retrieval with queries: {}", queries);

        // Step 6: 补充检索
        List<List<SearchResult>> supplementaryLists = supplementaryRetriever.retrieve(queries, permissionFilter);
        if (supplementaryLists.isEmpty()) {
            logger.warn("Supplementary retrieval returned no results");
            return new ReflectionPipelineResult(
                    initialFilterResult.finalDocs(),
                    reflectionResult,
                    true,
                    queries
            );
        }

        // Step 7: 合并原始召回与补充召回
        List<List<SearchResult>> mergedResultLists = new ArrayList<>(originalResultLists);
        mergedResultLists.addAll(supplementaryLists);
        logger.info("Merged result lists: {} original + {} supplementary = {} total",
                originalResultLists.size(), supplementaryLists.size(), mergedResultLists.size());

        // Step 8: 重新 RRF + Cross-Encoder + MMR/Direct Top-K
        CandidateFilterPipeline.FilterResult reRankedResult = candidateFilterPipeline.filter(
                rawQuery, intent, mergedResultLists);
        logger.info("Re-ranked after supplementary retrieval: {} final docs",
                reRankedResult.finalDocs().size());

        return new ReflectionPipelineResult(
                reRankedResult.finalDocs(),
                reflectionResult,
                true,
                queries
        );
    }

    /**
     * 反思与补充层结果。
     */
    public record ReflectionPipelineResult(
            List<SearchResult> finalDocs,
            ReflectionResult reflectionResult,
            boolean usedSupplementaryRetrieval,
            List<String> supplementaryQueries) {
    }
}
