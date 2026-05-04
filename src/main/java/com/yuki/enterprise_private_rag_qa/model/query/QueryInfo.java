package com.yuki.enterprise_private_rag_qa.model.query;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/**
 * 统一 query 数据结构，携带整个 RAG 链路的 query 信息。
 * raw_query 是整个系统的意图锚点，不可丢弃。
 */
@Data
public class QueryInfo {
    /** 用户原始 query（意图锚点，永不丢弃） */
    private String rawQuery;

    /** 规范化后 query（全角半角、标点、空格） */
    private String normalizedQuery;

    /** 面向 BM25 的清洗后 query（去客套词、语气词） */
    private String cleanedQuery;

    /** 意图：原因分析/实现方法/概念解释/对比分析/故障排查/查找定位/通用问答 */
    private String intent;

    /** 核心实体列表 */
    private List<String> entities = new ArrayList<>();

    /** 检索关键词列表 */
    private List<String> keywords = new ArrayList<>();

    /** 主改写 query */
    private String mainRewrittenQuery;

    /** 多版本改写 query（3~5个） */
    private List<String> rewrittenQueries = new ArrayList<>();

    /** HyDE 假设文档（100~200字） */
    private String hydeDocument;
}
