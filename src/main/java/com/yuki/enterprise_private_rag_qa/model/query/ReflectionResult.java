package com.yuki.enterprise_private_rag_qa.model.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/**
 * 反思节点输出。
 */
@Data
public class ReflectionResult {
    /** context 是否足够支持回答用户问题 */
    @JsonProperty("is_sufficient")
    private boolean isSufficient;

    /** 判断置信度 0.0~1.0 */
    @JsonProperty("confidence")
    private double confidence;

    /** 问题列表 */
    @JsonProperty("problems")
    private List<String> problems = new ArrayList<>();

    /** 缺失的信息点 */
    @JsonProperty("missing_aspects")
    private List<String> missingAspects = new ArrayList<>();

    /** 补充检索 query（面向检索系统，非用户） */
    @JsonProperty("supplementary_queries")
    private List<String> supplementaryQueries = new ArrayList<>();
}
