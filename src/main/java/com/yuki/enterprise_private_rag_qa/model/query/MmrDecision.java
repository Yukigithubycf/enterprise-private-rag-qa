package com.yuki.enterprise_private_rag_qa.model.query;

import lombok.Data;

/**
 * MMR 门控判断结果。
 */
@Data
public class MmrDecision {
    /** 是否启用 MMR */
    private boolean useMmr;

    /** 判断原因（便于调试） */
    private String reason;

    /** 候选平均语义相似度（仅在通过相似度判断时计算） */
    private Double avgSimilarity;

    public static MmrDecision enable(String reason) {
        MmrDecision d = new MmrDecision();
        d.useMmr = true;
        d.reason = reason;
        return d;
    }

    public static MmrDecision disable(String reason) {
        MmrDecision d = new MmrDecision();
        d.useMmr = false;
        d.reason = reason;
        return d;
    }
}
