package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 多路 RRF（Reciprocal Rank Fusion）融合服务。
 * 将多个召回路的结果按 rank 融合为一个统一的粗排序列表。
 *
 * 公式：RRF(d) = Σ 1 / (k + rank_i(d))
 * k 默认为 60。
 */
@Component
public class RrfFusionService {

    private static final Logger logger = LoggerFactory.getLogger(RrfFusionService.class);
    private static final int DEFAULT_K = 60;

    /**
     * 对多路召回结果进行 RRF 融合。
     *
     * @param resultLists 多路召回结果列表（每路内部已按 rank 排序）
     * @param k           RRF 平滑参数
     * @return RRF 排序后的候选列表（含 rrfScore, rrfRank, retrievalSources）
     */
    public List<SearchResult> fuse(List<List<SearchResult>> resultLists, int k) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, FusedEntry> fused = new LinkedHashMap<>();

        for (List<SearchResult> results : resultLists) {
            if (results == null || results.isEmpty()) continue;

            Set<String> seenInCurrentRoute = new HashSet<>();
            for (int i = 0; i < results.size(); i++) {
                SearchResult doc = results.get(i);
                String key = buildResultKey(doc);
                if (!seenInCurrentRoute.add(key)) {
                    continue; // 同路内去重
                }

                int rank = i + 1;
                double rrfScore = 1.0 / (k + rank);
                double originalScore = doc.getScore() != null ? doc.getScore() : 0.0;

                FusedEntry entry = fused.computeIfAbsent(key, ignored -> {
                    SearchResult copy = copyForFusion(doc);
                    return new FusedEntry(copy);
                });
                entry.addSource(doc.getRetrievalSource(), rank, rrfScore, originalScore);
            }
        }

        // 按 RRF 分数降序排序
        List<SearchResult> fusedDocs = new ArrayList<>();
        int rrfRank = 0;
        for (FusedEntry entry : fused.values()) {
            rrfRank++;
            SearchResult doc = entry.result;
            doc.setRrfScore(entry.totalRrfScore);
            doc.setRrfRank(rrfRank);
            doc.setRetrievalSources(new ArrayList<>(entry.sources));
            doc.setScore(entry.totalRrfScore); // 用 RRF 分数替代原始分数用于后续排序
            fusedDocs.add(doc);
        }

        fusedDocs.sort(Comparator.comparingDouble(SearchResult::getRrfScore).reversed());

        // 重新分配 rrf_rank
        for (int i = 0; i < fusedDocs.size(); i++) {
            fusedDocs.get(i).setRrfRank(i + 1);
        }

        logger.debug("RRF fusion: {} routes → {} unique candidates", resultLists.size(), fusedDocs.size());
        return fusedDocs;
    }

    /**
     * 使用默认 k=60 的便捷方法。
     */
    public List<SearchResult> fuse(List<List<SearchResult>> resultLists) {
        return fuse(resultLists, DEFAULT_K);
    }

    private String buildResultKey(SearchResult doc) {
        String fileMd5 = doc.getFileMd5() != null ? doc.getFileMd5() : "unknown";
        String chunkId = doc.getChunkId() != null ? doc.getChunkId().toString() : "0";
        return fileMd5 + "#" + chunkId;
    }

    private SearchResult copyForFusion(SearchResult source) {
        return new SearchResult(
                source.getFileMd5(),
                source.getChunkId(),
                source.getParentId(),
                source.getTextContent(),
                source.getParentTextContent(),
                source.getScore(),
                source.getUserId(),
                source.getOrgTag(),
                Boolean.TRUE.equals(source.getIsPublic()),
                source.getFileName()
        );
    }

    private static class FusedEntry {
        final SearchResult result;
        double totalRrfScore;
        double bestOriginalScore;
        final Set<String> sources = new LinkedHashSet<>();

        FusedEntry(SearchResult result) {
            this.result = result;
            this.bestOriginalScore = result.getScore() != null ? result.getScore() : 0.0;
        }

        void addSource(String source, int rank, double rrfScore, double originalScore) {
            this.totalRrfScore += rrfScore;
            this.bestOriginalScore = Math.max(this.bestOriginalScore, originalScore);
            if (source != null) {
                this.sources.add(source);
            }
        }
    }
}
