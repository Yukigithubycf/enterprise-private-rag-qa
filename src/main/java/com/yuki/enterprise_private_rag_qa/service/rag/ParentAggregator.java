package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 6.4: 父子块聚合器。
 *
 * 将 child-level 检索结果按父块聚合，以父块完整文本作为 context。
 * 解决 child chunk 信息碎片化的问题，让 LLM 获得更完整的段落上下文。
 *
 * 聚合规则：
 *   1. 按 parent key（fileMd5 + parentId）分组，无 parentId 则用 chunkId
 *   2. 每组取 finalRank 最小的（即最靠前的）chunk
 *   3. 优先使用 parentTextContent，无则用 textContent
 *   4. 按 finalRank 重新排序，截断至 topK
 */
@Component
public class ParentAggregator {

    private static final Logger logger = LoggerFactory.getLogger(ParentAggregator.class);

    /**
     * 将 child 级结果聚合为 parent 级结果。
     *
     * @param childResults child 级检索结果（已含 finalRank）
     * @param topK         最终返回的 parent 数量
     * @return parent 级结果列表
     */
    public List<SearchResult> aggregate(List<SearchResult> childResults, int topK) {
        if (childResults == null || childResults.isEmpty()) {
            return List.of();
        }

        Map<String, SearchResult> parentMap = new LinkedHashMap<>();

        for (SearchResult child : childResults) {
            String parentKey = buildParentKey(child);

            SearchResult existing = parentMap.get(parentKey);
            if (existing == null || child.getFinalRank() < existing.getFinalRank()) {
                SearchResult parentResult = toParentResult(child);
                // Preserve parentTextContent from the existing entry if the new entry lacks it
                if (existing != null && isBlank(parentResult.getParentTextContent())
                        && !isBlank(existing.getParentTextContent())) {
                    parentResult.setParentTextContent(existing.getParentTextContent());
                    parentResult.setTextContent(existing.getParentTextContent());
                }
                parentMap.put(parentKey, parentResult);
            } else if (isBlank(existing.getParentTextContent()) && !isBlank(child.getParentTextContent())) {
                // Same parent, keep the best-ranked child's score but fill in missing parent text
                existing.setParentTextContent(child.getParentTextContent());
                existing.setTextContent(child.getParentTextContent());
            }
        }

        List<SearchResult> results = new ArrayList<>(parentMap.values());
        results.sort(Comparator.comparingInt(SearchResult::getFinalRank));

        int limit = Math.min(topK, results.size());
        List<SearchResult> truncated = results.subList(0, limit);

        // Re-assign final rank
        for (int i = 0; i < truncated.size(); i++) {
            truncated.get(i).setFinalRank(i + 1);
        }

        logger.debug("Parent aggregation: {} child chunks → {} parent blocks", childResults.size(), truncated.size());
        return truncated;
    }

    private SearchResult toParentResult(SearchResult child) {
        SearchResult parent = new SearchResult(
                child.getFileMd5(),
                child.getChunkId(),
                child.getParentId(),
                resolveText(child),
                child.getParentTextContent(),
                child.getScore(),
                child.getUserId(),
                child.getOrgTag(),
                Boolean.TRUE.equals(child.getIsPublic()),
                child.getFileName()
        );
        // Copy tracking fields
        parent.setRetrievalSource(child.getRetrievalSource());
        parent.setRank(child.getRank());
        parent.setQueryUsed(child.getQueryUsed());
        parent.setRrfScore(child.getRrfScore());
        parent.setRrfRank(child.getRrfRank());
        parent.setCrossScore(child.getCrossScore());
        parent.setCrossRank(child.getCrossRank());
        parent.setMmrScore(child.getMmrScore());
        parent.setFinalRank(child.getFinalRank());
        parent.setRetrievalSources(child.getRetrievalSources());
        return parent;
    }

    private String resolveText(SearchResult child) {
        if (!isBlank(child.getParentTextContent())) {
            return child.getParentTextContent();
        }
        return child.getTextContent() != null ? child.getTextContent() : "";
    }

    private String buildParentKey(SearchResult result) {
        if (!isBlank(result.getParentId())) {
            return result.getFileMd5() + "#" + result.getParentId();
        }
        return result.getFileMd5() + "#" + result.getChunkId();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
