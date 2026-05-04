package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 补充检索器。
 * 当 Reflection 节点判定 context 不足时，对 supplementary_queries 执行轻量检索。
 *
 * 检索策略（不重新生成 HyDE 或 Query Rewrite）：
 *   supplementary query + BM25（top_k=10）
 *   supplementary query + Dense Vector（top_k=10）
 *
 * 每个 supplementary query 产生 2 路结果，最多 3 个 query → 最多 6 路补充结果。
 */
@Component
public class SupplementaryRetriever {

    private static final Logger logger = LoggerFactory.getLogger(SupplementaryRetriever.class);

    private final HybridSearchService hybridSearchService;
    private final RagProperties ragProperties;

    public SupplementaryRetriever(HybridSearchService hybridSearchService, RagProperties ragProperties) {
        this.hybridSearchService = hybridSearchService;
        this.ragProperties = ragProperties;
    }

    /**
     * 对补充检索 query 执行 BM25 + Dense Vector 检索。
     * 每路结果会打上 retrievalSource、rank、queryUsed 标记。
     *
     * @param supplementaryQueries 补充检索 query 列表（最多 3 个）
     * @param permissionFilter     ES 权限过滤 Query
     * @return 补充检索结果列表（每路一个 List）
     */
    public List<List<SearchResult>> retrieve(List<String> supplementaryQueries, Query permissionFilter) {
        if (supplementaryQueries == null || supplementaryQueries.isEmpty()) {
            return List.of();
        }

        int bm25TopK = ragProperties.getReflection().getSupplementaryBm25TopK();
        int vectorTopK = ragProperties.getReflection().getSupplementaryVectorTopK();
        List<List<SearchResult>> resultLists = new ArrayList<>();

        for (String q : supplementaryQueries) {
            if (q == null || q.isBlank()) {
                continue;
            }

            // BM25 检索
            try {
                List<SearchResult> bm25Results = hybridSearchService.keywordSearchByText(q, permissionFilter, bm25TopK, false);
                tagResults(bm25Results, "reflection_bm25", q);
                if (!bm25Results.isEmpty()) {
                    resultLists.add(bm25Results);
                    logger.debug("Supplementary BM25: query='{}', results={}", q, bm25Results.size());
                }
            } catch (Exception e) {
                logger.warn("Supplementary BM25 retrieval failed for query='{}': {}", q, e.getMessage());
            }

            // Dense Vector 检索
            try {
                List<SearchResult> vectorResults = hybridSearchService.semanticSearchByText(q, permissionFilter, vectorTopK);
                tagResults(vectorResults, "reflection_vector", q);
                if (!vectorResults.isEmpty()) {
                    resultLists.add(vectorResults);
                    logger.debug("Supplementary Vector: query='{}', results={}", q, vectorResults.size());
                }
            } catch (Exception e) {
                logger.warn("Supplementary Vector retrieval failed for query='{}': {}", q, e.getMessage());
            }
        }

        logger.info("Supplementary retrieval: {} queries → {} result lists", supplementaryQueries.size(), resultLists.size());
        return resultLists;
    }

    private void tagResults(List<SearchResult> results, String source, String queryUsed) {
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            r.setRetrievalSource(source);
            r.setRank(i + 1);
            r.setQueryUsed(queryUsed);
        }
    }
}
