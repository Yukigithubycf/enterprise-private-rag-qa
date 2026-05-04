package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * LLM 驱动的 HyDE（假设文档嵌入）生成。
 * 生成一段 100~200 字的"假设性答案文档"，用于向量检索。
 * HyDE 文档不是事实来源，只是检索探针。
 */
@Service
public class HydeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(HydeGenerator.class);
    private static final String SYSTEM_PROMPT = "你是 RAG 系统中的 HyDE 生成器。只输出假设文档正文，不输出其他内容。";

    private static final String BASE_PROMPT_TEMPLATE = """
            你是 RAG 系统中的 HyDE 生成器。

            你的任务是根据用户问题和主改写 query，生成一段"假设性答案文档"，用于向量检索。

            注意：
            1. 不要真正回答用户，只生成用于检索的假设文档。
            2. 假设文档应该像知识库中的说明性段落，而不是关键词列表。
            3. 必须围绕 main_rewritten_query。
            4. 必须保留 raw_query 的原始意图。
            5. 不要引入与问题无关的新主题。
            6. 不要编造具体论文名、数据、实验结果、API 名称或不存在的事实。
            7. 长度控制在 100 到 200 字。
            8. 输出纯文本，不要 JSON。

            raw_query:
            {raw_query}

            normalized_query:
            {normalized_query}

            intent:
            {intent}

            entities:
            {entities}

            main_rewritten_query:
            {main_rewritten_query}

            {intent_constraint}

            请生成 HyDE document:""";

    /**
     * 不同 intent 的 HyDE 生成约束。
     */
    private static final Map<String, String> INTENT_CONSTRAINTS = Map.of(
            "实现方法", "生成一段说明如何实现该功能的假设文档，覆盖流程、输入输出、关键模块和工程注意事项。",
            "原因分析", "生成一段解释该问题可能原因的假设文档，覆盖触发原因、错误机制、排查方向和缓解策略。",
            "概念解释", "生成一段解释该概念的假设文档，覆盖定义、作用、典型场景和相关术语。",
            "对比分析", "生成一段比较两个概念或方法的假设文档，覆盖相同点、差异点、适用场景和取舍。",
            "故障排查", "生成一段说明该故障可能原因和排查步骤的假设文档，覆盖常见错误场景和检查点。",
            "总结归纳", "生成一段对该主题进行总结归纳的假设文档，覆盖核心要点、分类和关键结论。"
    );

    private final RagLlmClient llmClient;

    public HydeGenerator(RagLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 生成 HyDE 假设文档。
     *
     * @param rawQuery           原始 query
     * @param normalizedQuery    规范化 query
     * @param intent             意图
     * @param entities           实体列表
     * @param mainRewrittenQuery 主改写 query
     * @return HyDE 假设文档文本，失败时返回 null
     */
    public String generate(String rawQuery, String normalizedQuery, String intent,
                           List<String> entities, String mainRewrittenQuery) {
        try {
            String entitiesStr = entities != null ? String.join(", ", entities) : "";
            String constraint = INTENT_CONSTRAINTS.getOrDefault(intent,
                    "生成一段与用户问题相关的假设知识库文档段落。");

            String prompt = BASE_PROMPT_TEMPLATE
                    .replace("{raw_query}", rawQuery)
                    .replace("{normalized_query}", normalizedQuery)
                    .replace("{intent}", intent != null ? intent : "通用问答")
                    .replace("{entities}", entitiesStr)
                    .replace("{main_rewritten_query}", mainRewrittenQuery)
                    .replace("{intent_constraint}", constraint);

            logger.debug("HyDE generation prompt for intent: {}", intent);
            String hydeDoc = llmClient.chatSync(SYSTEM_PROMPT, prompt, 0.3);

            if (hydeDoc == null || hydeDoc.isBlank()) {
                logger.warn("HyDE generated empty document, returning null");
                return null;
            }

            // 截断过长内容
            if (hydeDoc.length() > 400) {
                hydeDoc = hydeDoc.substring(0, 400);
            }

            logger.debug("HyDE document generated, length: {}", hydeDoc.length());
            return hydeDoc.trim();

        } catch (Exception e) {
            logger.error("HyDE generation failed: {}", e.getMessage(), e);
            // Fallback: null，跳过 HyDE 召回路
            return null;
        }
    }
}
