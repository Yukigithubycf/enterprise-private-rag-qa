package com.yuki.enterprise_private_rag_qa.entity;

/**
 * 召回路标识枚举。
 * 用于标记每条检索结果的来源，便于 RRF 融合后的来源追踪和调试。
 */
public enum RetrievalSource {

    RAW_BM25("raw_bm25"),
    RAW_VECTOR("raw_vector"),
    CLEANED_BM25("cleaned_bm25"),
    REWRITE_VECTOR("rewrite_vector"),
    REWRITE_BM25("rewrite_bm25"),
    HYDE_VECTOR("hyde_vector"),
    ENTITY_BM25("entity_bm25"),
    REFLECTION_BM25("reflection_bm25"),
    REFLECTION_VECTOR("reflection_vector");

    private final String label;

    RetrievalSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
