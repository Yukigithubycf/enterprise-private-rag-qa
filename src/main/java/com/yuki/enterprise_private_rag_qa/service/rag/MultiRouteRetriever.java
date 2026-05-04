package com.yuki.enterprise_private_rag_qa.service.rag;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import com.yuki.enterprise_private_rag_qa.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多路召回编排器（Stage 2）。
 * 根据 QueryInfo 驱动 7 路检索，返回带来源标记的候选结果列表。
 *
 * 7 路召回：
 *   R1 - raw query + BM25
 *   R2 - raw query + Dense Vector
 *   R3 - cleaned query + BM25
 *   R4 - rewritten queries + Dense Vector
 *   R5 - rewritten queries + BM25
 *   R6 - HyDE document + Dense Vector
 *   R7 - entity-based BM25
 */
@Service
public class MultiRouteRetriever {

    private static final Logger logger = LoggerFactory.getLogger(MultiRouteRetriever.class);

    private final HybridSearchService searchService;
    private final RagProperties ragProperties;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public MultiRouteRetriever(HybridSearchService searchService, RagProperties ragProperties) {
        this.searchService = searchService;
        this.ragProperties = ragProperties;
    }

    /**
     * 执行多路召回。
     *
     * @param queryInfo Stage 1 输出的完整 query 信息
     * @param userId    用户 ID（用于权限过滤）
     * @return 各路检索结果列表（每个元素是一路召回的结果列表）
     */
    public List<List<SearchResult>> retrieve(QueryInfo queryInfo, String userId) {
        logger.info("Starting multi-route retrieval for: {}", queryInfo.getRawQuery());
        long startTime = System.currentTimeMillis();

        Query permissionFilter = searchService.buildPermissionFilter(userId);
        RagProperties.Retrieval cfg = ragProperties.getRetrieval();

        List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();

        // R1: raw query + BM25
        futures.add(CompletableFuture.supplyAsync(
                () -> tagAndReturn(
                        searchService.keywordSearchByText(queryInfo.getRawQuery(), permissionFilter, cfg.getRawBm25TopK(), true),
                        "raw_bm25", queryInfo.getRawQuery()),
                executor));

        // R2: raw query + Dense Vector
        futures.add(CompletableFuture.supplyAsync(
                () -> tagAndReturn(
                        searchService.semanticSearchByText(queryInfo.getRawQuery(), permissionFilter, cfg.getRawVectorTopK()),
                        "raw_vector", queryInfo.getRawQuery()),
                executor));

        // R3: cleaned query + BM25（仅当 cleaned query 与 raw query 不同时）
        String cleanedQuery = queryInfo.getCleanedQuery();
        if (cleanedQuery != null && !cleanedQuery.isBlank() && !cleanedQuery.equals(queryInfo.getRawQuery())) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> tagAndReturn(
                            searchService.keywordSearchByText(cleanedQuery, permissionFilter, cfg.getCleanedBm25TopK(), false),
                            "cleaned_bm25", cleanedQuery),
                    executor));
        }

        // R4+R5: rewritten queries（每个改写 query 分别做 Dense Vector 和 BM25）
        List<String> rewritten = queryInfo.getRewrittenQueries();
        if (rewritten != null) {
            for (String q : rewritten) {
                if (q == null || q.isBlank()) continue;
                futures.add(CompletableFuture.supplyAsync(
                        () -> tagAndReturn(
                                searchService.semanticSearchByText(q, permissionFilter, cfg.getRewriteVectorTopK()),
                                "rewrite_vector", q),
                        executor));
                futures.add(CompletableFuture.supplyAsync(
                        () -> tagAndReturn(
                                searchService.keywordSearchByText(q, permissionFilter, cfg.getRewriteBm25TopK(), false),
                                "rewrite_bm25", q),
                        executor));
            }
        }

        // R6: HyDE document + Dense Vector
        String hydeDoc = queryInfo.getHydeDocument();
        if (hydeDoc != null && !hydeDoc.isBlank()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> tagAndReturn(
                            searchService.semanticSearchByText(hydeDoc, permissionFilter, cfg.getHydeVectorTopK()),
                            "hyde_vector", hydeDoc),
                    executor));
        }

        // R7: entity-based BM25
        List<String> entities = queryInfo.getEntities();
        if (entities != null) {
            for (String entity : entities) {
                if (entity == null || entity.isBlank()) continue;
                futures.add(CompletableFuture.supplyAsync(
                        () -> tagAndReturn(
                                searchService.keywordSearchByText(entity, permissionFilter, cfg.getEntityBm25TopK(), false),
                                "entity_bm25", entity),
                        executor));
            }
        }

        // 等待所有召回路完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 收集结果
        List<List<SearchResult>> resultLists = new ArrayList<>();
        for (CompletableFuture<List<SearchResult>> future : futures) {
            try {
                List<SearchResult> results = future.get();
                if (!results.isEmpty()) {
                    resultLists.add(results);
                }
            } catch (Exception e) {
                logger.error("A retrieval route failed: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int totalChunks = resultLists.stream().mapToInt(List::size).sum();
        logger.info("Multi-route retrieval completed in {}ms. {} routes returned, {} total chunks",
                elapsed, resultLists.size(), totalChunks);

        return resultLists;
    }

    /**
     * 为检索结果打上来源标记和排名。
     */
    private List<SearchResult> tagAndReturn(List<SearchResult> rawResults, String source, String queryUsed) {
        List<SearchResult> tagged = new ArrayList<>();
        int rank = 0;
        for (SearchResult doc : rawResults) {
            rank++;
            doc.setRetrievalSource(source);
            doc.setRank(rank);
            doc.setQueryUsed(queryUsed);
            tagged.add(doc);
        }
        return tagged;
    }
}
