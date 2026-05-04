package com.yuki.enterprise_private_rag_qa.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 面向 BM25 的 Query 清洗。
 * 去掉客套词、语气词、无检索价值的口语表达，保留核心实体和意图关键词。
 * 纯代码实现，无 LLM 调用。
 */
@Component
public class QueryCleaner {

    private static final Logger logger = LoggerFactory.getLogger(QueryCleaner.class);

    private static final String[] NOISE_WORDS = {
            "请问", "麻烦", "帮我", "可以", "能不能", "能否",
            "一下", "呢", "啊", "吗", "这个", "那个",
            "我想", "想知道", "想问", "请教", "请问一下"
    };

    private static final Pattern PUNCT_PATTERN = Pattern.compile("[?,，。！？\\s]+");

    /**
     * 对规范化后的 query 进行 BM25 清洗。
     * 注意：cleaned_query 不适合作为 Query Rewrite 或 HyDE 的唯一输入，
     * 因为它可能丢失完整语义和疑问意图。
     */
    public String clean(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return normalizedQuery;
        }

        String query = normalizedQuery;

        // 1. 去除噪声词
        for (String word : NOISE_WORDS) {
            query = query.replace(word, "");
        }

        // 2. 保留意图信息的关键词替换（注意不要粗暴删除）
        query = query.replace("为什么", "原因");
        query = query.replace("为何", "原因");
        query = query.replace("怎么", "实现");
        query = query.replace("如何", "实现");
        query = query.replace("是什么", "");  // "X 是什么" → 保留 X 作为关键词

        // 3. 去除残留标点和多余空格
        query = PUNCT_PATTERN.matcher(query).replaceAll(" ").trim();

        // 4. 如果清洗后变空，退回原始 normalized query
        if (query.isBlank()) {
            logger.debug("Cleaning resulted in empty query, falling back to normalized query");
            return normalizedQuery;
        }

        logger.debug("Cleaning: [{}] → [{}]", normalizedQuery, query);
        return query;
    }
}
