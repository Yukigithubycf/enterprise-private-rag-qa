package com.yuki.enterprise_private_rag_qa.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Cross-Encoder 精排器（Phase 6.7 升级：支持真实模型 HTTP 调用 + stub fallback）。
 *
 * 当 rag.cross-encoder.api-url 配置了有效的 API 地址时，通过 HTTP 调用真实
 * Cross-Encoder 模型（如 bge-reranker-v2-m3）进行精排。
 * API 不可用或未配置时，降级为 stub 模式（RRF score 归一化）。
 *
 * 期望的 API 契约（兼容主流 reranker 服务）：
 *   POST {apiUrl}/rerank
 *   Request:  { "query": "...", "documents": ["...", ...] }
 *   Response: { "results": [ {"index": 0, "score": 0.95}, ... ] }
 */
@Component
public class CrossEncoderReranker {

    private static final Logger logger = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final boolean apiConfigured;

    public CrossEncoderReranker(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        String apiUrl = ragProperties.getCrossEncoder().getApiUrl();
        if (apiUrl != null && !apiUrl.isBlank()) {
            this.webClient = WebClient.builder()
                    .baseUrl(apiUrl)
                    .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();
            this.apiConfigured = true;
            logger.info("Cross-Encoder HTTP client configured: baseUrl={}", apiUrl);
        } else {
            this.webClient = null;
            this.apiConfigured = false;
            logger.info("Cross-Encoder API URL not configured, using stub mode");
        }
    }

    /**
     * 对候选 chunk 进行精排打分。
     * 优先使用真实模型 API，失败或未配置时使用 stub。
     *
     * @param rawQuery   原始用户 query
     * @param candidates 待精排的候选列表
     * @return 按 cross_score 降序排序的候选列表
     */
    public List<SearchResult> rerank(String rawQuery, List<SearchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        logger.debug("Cross-Encoder reranking {} candidates (apiConfigured={})", candidates.size(), apiConfigured);

        if (apiConfigured) {
            try {
                return rerankWithApi(rawQuery, candidates);
            } catch (Exception e) {
                logger.warn("Cross-Encoder API call failed: {}. Falling back to stub mode.", e.getMessage());
            }
        }

        return rerankStub(candidates);
    }

    /**
     * 通过 HTTP API 调用真实 Cross-Encoder 模型。
     */
    private List<SearchResult> rerankWithApi(String rawQuery, List<SearchResult> candidates) {
        List<SearchResult> allScored = new ArrayList<>();

        // 分批处理（避免请求体过大）
        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, candidates.size());
            List<SearchResult> batch = candidates.subList(start, end);
            List<String> documents = batch.stream()
                    .map(d -> d.getTextContent() != null ? d.getTextContent() : "")
                    .toList();

            double[] scores = callRerankApi(rawQuery, documents);

            for (int i = 0; i < batch.size(); i++) {
                SearchResult doc = batch.get(i);
                doc.setCrossScore(i < scores.length ? scores[i] : 0.0);
                allScored.add(doc);
            }
        }

        // 按 cross_score 降序排序
        List<SearchResult> ranked = allScored.stream()
                .sorted(Comparator.comparingDouble(SearchResult::getCrossScore).reversed())
                .toList();

        // 分配 cross_rank
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setCrossRank(i + 1);
        }

        logger.debug("Cross-Encoder API rerank complete: {} candidates, top score: {:.3f}",
                ranked.size(),
                ranked.isEmpty() ? 0.0 : ranked.get(0).getCrossScore());

        return ranked;
    }

    /**
     * 调用 Cross-Encoder API，返回每个 document 的相关性分数。
     */
    private double[] callRerankApi(String query, List<String> documents) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "query", query,
                    "documents", documents
            );

            String response = webClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block(REQUEST_TIMEOUT);

            return parseRerankResponse(response, documents.size());
        } catch (Exception e) {
            throw new RuntimeException("Cross-Encoder API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 rerank API 响应中的 scores 数组。
     * 兼容多种响应格式：
     *   - { "results": [ {"index": 0, "score": 0.95}, ... ] }
     *   - { "scores": [0.95, 0.87, ...] }
     *   - [0.95, 0.87, ...]
     */
    private double[] parseRerankResponse(String responseBody, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 格式 1: { "results": [...] }
            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                double[] scores = new double[expectedCount];
                for (JsonNode item : results) {
                    int index = item.get("index").asInt();
                    double score = item.get("score").asDouble();
                    if (index < expectedCount) {
                        scores[index] = score;
                    }
                }
                return scores;
            }

            // 格式 2: { "scores": [...] } 或裸数组
            JsonNode scoresNode = root.get("scores");
            if (scoresNode == null && root.isArray()) {
                scoresNode = root;
            }
            if (scoresNode != null && scoresNode.isArray()) {
                double[] scores = new double[scoresNode.size()];
                for (int i = 0; i < scoresNode.size(); i++) {
                    scores[i] = scoresNode.get(i).asDouble();
                }
                return scores;
            }

            logger.warn("Unexpected rerank API response format: {}", responseBody);
            return new double[0];
        } catch (Exception e) {
            logger.error("Failed to parse rerank response: {}", e.getMessage());
            return new double[0];
        }
    }

    /**
     * Stub 实现：RRF score 归一化为 cross_score（Phase 3 原逻辑）。
     */
    private List<SearchResult> rerankStub(List<SearchResult> candidates) {
        logger.debug("Cross-Encoder reranking {} candidates (stub mode)", candidates.size());

        double maxScore = candidates.stream()
                .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0.0)
                .max()
                .orElse(1.0);

        for (SearchResult doc : candidates) {
            double rawScore = doc.getScore() != null ? doc.getScore() : 0.0;
            double normalizedScore = maxScore > 0 ? rawScore / maxScore : 0.0;
            doc.setCrossScore(normalizedScore);
        }

        List<SearchResult> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(SearchResult::getCrossScore).reversed())
                .toList();

        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setCrossRank(i + 1);
        }

        return ranked;
    }
}
