package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 候选去重服务。
 * 两层去重：chunk_id 去重 + content MD5 hash 去重。
 * 不做强语义去重（语义重复交给 MMR 处理）。
 */
@Component
public class DeduplicationService {

    private static final Logger logger = LoggerFactory.getLogger(DeduplicationService.class);

    /**
     * 对多路召回结果展开、去重。
     *
     * @param resultLists 多路召回结果列表
     * @return 去重后的候选列表（保留最高分的副本）
     */
    public List<SearchResult> deduplicate(List<List<SearchResult>> resultLists) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }

        // 展开所有结果
        List<SearchResult> all = resultLists.stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        int beforeCount = all.size();

        // 第一层：chunk_id 去重 — 按 (fileMd5, chunkId) 组合键
        Map<String, SearchResult> idMap = new LinkedHashMap<>();
        for (SearchResult doc : all) {
            String key = buildIdKey(doc);
            idMap.merge(key, doc, (existing, incoming) ->
                    scoreOf(existing) >= scoreOf(incoming) ? existing : incoming);
        }

        // 第二层：content hash 去重
        Map<String, SearchResult> hashMap = new LinkedHashMap<>();
        for (SearchResult doc : idMap.values()) {
            String hash = contentHash(doc.getTextContent());
            hashMap.merge(hash, doc, (existing, incoming) ->
                    scoreOf(existing) >= scoreOf(incoming) ? existing : incoming);
        }

        List<SearchResult> deduped = new ArrayList<>(hashMap.values());

        logger.debug("Dedup: {} → {} (id dedup) → {} (hash dedup)", beforeCount, idMap.size(), deduped.size());
        return deduped;
    }

    private String buildIdKey(SearchResult doc) {
        String fileMd5 = doc.getFileMd5() != null ? doc.getFileMd5() : "unknown";
        String chunkId = doc.getChunkId() != null ? doc.getChunkId().toString() : "0";
        return fileMd5 + "#" + chunkId;
    }

    private static String contentHash(String text) {
        if (text == null || text.isBlank()) {
            return UUID.randomUUID().toString(); // 空内容视为唯一
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    private static double scoreOf(SearchResult doc) {
        Double s = doc.getScore();
        return s != null ? s : 0.0;
    }
}
