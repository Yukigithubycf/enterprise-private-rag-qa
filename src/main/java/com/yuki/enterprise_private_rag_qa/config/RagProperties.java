package com.yuki.enterprise_private_rag_qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG Pipeline 配置属性。
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Data
public class RagProperties {

    private Pipeline pipeline = new Pipeline();
    private Intent intent = new Intent();
    private Retrieval retrieval = new Retrieval();
    private Rrf rrf = new Rrf();
    private CrossEncoder crossEncoder = new CrossEncoder();
    private Mmr mmr = new Mmr();
    private FinalSelection finalSelection = new FinalSelection();
    private Reflection reflection = new Reflection();

    @Data
    public static class Pipeline {
        private boolean enableQueryRewrite = true;
        private boolean enableHyde = true;
        private boolean enableReflection = true;
        private int maxReflectionRounds = 1;
    }

    @Data
    public static class Intent {
        /** 是否启用 LLM 意图识别（Phase 6.1） */
        private boolean enableLlm = false;
        /** LLM 意图识别的 temperature */
        private double llmTemperature = 0.1;
    }

    @Data
    public static class Retrieval {
        private int rawBm25TopK = 30;
        private int rawVectorTopK = 30;
        private int cleanedBm25TopK = 20;
        private int rewriteVectorTopK = 15;
        private int rewriteBm25TopK = 10;
        private int hydeVectorTopK = 30;
        private int entityBm25TopK = 10;
    }

    @Data
    public static class Rrf {
        private int k = 60;
        private int topN = 80;
    }

    @Data
    public static class CrossEncoder {
        private String model = "BAAI/bge-reranker-v2-m3";
        private String apiUrl;
    }

    @Data
    public static class Mmr {
        private double lambda = 0.7;
        private double similarityThreshold = 0.82;
        private int checkTopN = 20;
        /** Phase 6.2: cross_score(top1) - cross_score(top2) 超过此阈值时跳过 MMR */
        private double cliffThreshold = 0.3;
    }

    @Data
    public static class FinalSelection {
        private int topK = 8;
        /** Phase 6.4: 是否启用父块聚合 */
        private boolean enableParentAggregation = true;
    }

    @Data
    public static class Reflection {
        private String model = "";
        private double temperature = 0.1;
        private int supplementaryBm25TopK = 10;
        private int supplementaryVectorTopK = 10;
    }
}
