package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.model.query.QueryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stage 1 Query 理解层编排器。
 * 串联 Normalization → Cleaning → Intent Detection → Entity Extraction → Query Rewrite → HyDE Generation。
 *
 * 执行顺序（有依赖）：
 *   step1: Normalization
 *   step2: Cleaning ──┐
 *   step3: Intent    ──┼── 三者可并行（均仅依赖 normalized_query）
 *   step4: Entity    ──┘
 *   step5: Query Rewrite（依赖 step1~4 全部输出）
 *   step6: HyDE Generation（依赖 main_rewritten_query）
 */
@Service
public class QueryPipeline {

    private static final Logger logger = LoggerFactory.getLogger(QueryPipeline.class);

    private final QueryNormalizer normalizer;
    private final QueryCleaner cleaner;
    private final IntentDetector intentDetector;
    private final EntityExtractor entityExtractor;
    private final QueryRewriter queryRewriter;
    private final HydeGenerator hydeGenerator;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public QueryPipeline(QueryNormalizer normalizer,
                         QueryCleaner cleaner,
                         IntentDetector intentDetector,
                         EntityExtractor entityExtractor,
                         QueryRewriter queryRewriter,
                         HydeGenerator hydeGenerator) {
        this.normalizer = normalizer;
        this.cleaner = cleaner;
        this.intentDetector = intentDetector;
        this.entityExtractor = entityExtractor;
        this.queryRewriter = queryRewriter;
        this.hydeGenerator = hydeGenerator;
    }

    /**
     * 执行完整的 Query 理解流程。
     *
     * @param rawQuery     用户原始问题
     * @param chatHistory  对话历史（可为 null）
     * @return 填充完整的 QueryInfo
     */
    public QueryInfo process(String rawQuery, String chatHistory) {
        logger.info("Starting QueryPipeline for: {}", rawQuery);
        long startTime = System.currentTimeMillis();

        QueryInfo info = new QueryInfo();
        info.setRawQuery(rawQuery);

        // Step 1: Normalization
        String normalizedQuery = normalizer.normalize(rawQuery);
        info.setNormalizedQuery(normalizedQuery);

        // Step 2~4: 并行执行 Cleaning、Intent Detection、Entity Extraction
        CompletableFuture<String> cleaningFuture = CompletableFuture.supplyAsync(
                () -> cleaner.clean(normalizedQuery), executor);

        CompletableFuture<String> intentFuture = CompletableFuture.supplyAsync(
                () -> intentDetector.detect(normalizedQuery), executor);

        CompletableFuture<EntityExtractor.EntityResult> entityFuture = CompletableFuture.supplyAsync(
                () -> entityExtractor.extract(normalizedQuery, chatHistory), executor);

        // 等待三个并行任务完成
        CompletableFuture.allOf(cleaningFuture, intentFuture, entityFuture).join();

        try {
            info.setCleanedQuery(cleaningFuture.get());
            info.setIntent(intentFuture.get());
            EntityExtractor.EntityResult entityResult = entityFuture.get();
            info.setEntities(entityResult.entities());
            info.setKeywords(entityResult.keywords());
        } catch (Exception e) {
            logger.error("Parallel tasks failed: {}", e.getMessage(), e);
            // 设置 fallback 值
            if (info.getCleanedQuery() == null) info.setCleanedQuery(normalizedQuery);
            if (info.getIntent() == null) info.setIntent("通用问答");
        }

        // Step 5: Query Rewrite（依赖 step1~4 的全部输出）
        QueryRewriter.RewriteResult rewriteResult = queryRewriter.rewrite(
                info.getRawQuery(),
                info.getNormalizedQuery(),
                info.getCleanedQuery(),
                chatHistory,
                info.getIntent(),
                info.getEntities(),
                info.getKeywords()
        );
        info.setMainRewrittenQuery(rewriteResult.mainRewrittenQuery());
        info.setRewrittenQueries(rewriteResult.rewrittenQueries());

        // Step 6: HyDE Generation（依赖 main_rewritten_query）
        String hydeDoc = hydeGenerator.generate(
                info.getRawQuery(),
                info.getNormalizedQuery(),
                info.getIntent(),
                info.getEntities(),
                info.getMainRewrittenQuery()
        );
        info.setHydeDocument(hydeDoc);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("QueryPipeline completed in {}ms. Intent: {}, Entities: {}, Rewritten: {}, HyDE: {}",
                elapsed,
                info.getIntent(),
                info.getEntities().size(),
                info.getRewrittenQueries().size(),
                info.getHydeDocument() != null ? info.getHydeDocument().length() + " chars" : "null");

        return info;
    }
}
