package com.yuki.enterprise_private_rag_qa.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String fileMd5;    // 文件指纹
    private Integer chunkId;   // 文本分块序号
    private String parentId;   // 父块ID
    private String textContent; // 文本内容
    private String parentTextContent; // 父块完整文本
    private Double score;      // 搜索得分
    private String fileName;   // 原始文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开

    // === 多路召回 + RRF + Cross-Encoder + MMR 链路追踪字段 ===
    private String retrievalSource;   // 召回路标识：raw_bm25/raw_vector/cleaned_bm25/rewrite_vector/rewrite_bm25/hyde_vector/entity_bm25/reflection_bm25/reflection_vector
    private int rank;                 // 该路内部的排名（1-based）
    private String queryUsed;         // 本次检索使用的 query 文本
    private double rrfScore;          // RRF 融合分数
    private int rrfRank;              // RRF 排名
    private double crossScore;        // Cross-Encoder 精排分数
    private int crossRank;            // Cross-Encoder 排名
    private double mmrScore;          // MMR 多样性过滤分数
    private int finalRank;            // 最终排名
    private java.util.List<String> retrievalSources; // 多路来源列表（RRF 融合后）

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, null, textContent, null, score, null, null, false, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, null, textContent, null, score, null, null, false, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, null, textContent, null, score, userId, orgTag, isPublic, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, null, textContent, null, score, userId, orgTag, isPublic, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String parentId, String textContent, String parentTextContent,
                        Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.parentId = parentId;
        this.textContent = textContent;
        this.parentTextContent = parentTextContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
    }
}
