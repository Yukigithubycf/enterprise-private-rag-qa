package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图识别器（Phase 6.1 升级：可选 LLM 模式，规则匹配为 fallback）。
 *
 * 当 rag.intent.enable-llm=true 时，优先使用 LLM 进行意图分类；
 * LLM 失败或未启用时，退回到关键词规则匹配。
 */
@Component
public class IntentDetector {

    private static final Logger logger = LoggerFactory.getLogger(IntentDetector.class);

    private static final String VALID_INTENTS =
            "概念解释, 实现方法, 原因分析, 对比分析, 故障排查, 查找定位, 总结归纳, 通用问答";

    private static final String LLM_PROMPT = """
            你是一个查询意图分类器。请分析用户问题的意图，从以下类别中选择最匹配的一个：

            类别列表：
            - 概念解释：用户想了解某个概念、术语、技术的定义或介绍
            - 实现方法：用户想知道如何做、实现步骤、流程方法
            - 原因分析：用户想知道为什么、原因、导致某结果的因素
            - 对比分析：用户想比较两个或多个事物的区别、优缺点
            - 故障排查：用户遇到了错误、异常、故障，需要排查解决
            - 查找定位：用户想知道某内容在哪里、在哪个文件、哪个位置
            - 总结归纳：用户希望综合整理信息、归纳要点
            - 通用问答：无法归入以上类别的一般性问题

            要求：
            - 只输出类别名称，不要输出任何解释
            - 类别名称必须与上述列表完全一致

            用户问题：
            %s""";

    private final RagLlmClient llmClient;
    private final RagProperties ragProperties;

    public IntentDetector(RagLlmClient llmClient, RagProperties ragProperties) {
        this.llmClient = llmClient;
        this.ragProperties = ragProperties;
    }

    /**
     * 从规范化后的 query 中识别意图。
     *
     * @param normalizedQuery 规范化后的 query
     * @return 意图标签（中文）
     */
    public String detect(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "通用问答";
        }

        // Phase 6.1: 若启用 LLM 意图识别，先尝试 LLM
        if (ragProperties.getIntent().isEnableLlm()) {
            String llmIntent = detectWithLlm(normalizedQuery);
            if (llmIntent != null) {
                logger.debug("LLM intent detection: '{}' → {}", normalizedQuery, llmIntent);
                return llmIntent;
            }
            logger.debug("LLM intent detection failed, falling back to rule-based");
        }

        return detectWithRules(normalizedQuery);
    }

    /**
     * 使用 LLM 进行意图分类。
     *
     * @return 意图标签，失败时返回 null
     */
    private String detectWithLlm(String query) {
        try {
            String prompt = String.format(LLM_PROMPT, query);
            double temperature = ragProperties.getIntent().getLlmTemperature();
            String response = llmClient.chatSync(null, prompt, temperature);
            String intent = extractIntent(response);
            if (isValidIntent(intent)) {
                return intent;
            }
            logger.debug("LLM returned invalid intent: '{}'", intent);
            return null;
        } catch (Exception e) {
            logger.warn("LLM intent detection failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 关键词规则匹配（原始逻辑）。
     */
    private String detectWithRules(String normalizedQuery) {
        // 匹配顺序很重要：更具体的规则应放在前面
        if (containsAny(normalizedQuery, List.of("报错", "失败", "异常", "不收敛", "出错", "错误"))) {
            return "故障排查";
        }
        if (containsAny(normalizedQuery, List.of("区别", "对比", "相比", "差异", "不同", "优缺点"))) {
            return "对比分析";
        }
        if (containsAny(normalizedQuery, List.of("为什么", "原因", "为何", "导致", "因为"))) {
            return "原因分析";
        }
        if (containsAny(normalizedQuery, List.of("如何", "怎么", "实现", "代码", "步骤", "流程", "方法"))) {
            return "实现方法";
        }
        if (containsAny(normalizedQuery, List.of("在哪里", "哪个文件", "哪一节", "位置", "路径", "定义在"))) {
            return "查找定位";
        }
        if (containsAny(normalizedQuery, List.of("总结", "概括", "整理", "归纳", "汇总"))) {
            return "总结归纳";
        }
        if (containsAny(normalizedQuery, List.of("是什么", "介绍", "解释", "讲一下", "概念", "定义", "什么叫"))) {
            return "概念解释";
        }

        return "通用问答";
    }

    /**
     * 从 LLM 响应中提取意图标签。
     */
    private String extractIntent(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        // 去除可能的前后缀空白和引号
        return response.trim()
                .replace("\"", "")
                .replace("'", "")
                .replace("。", "")
                .replace(".", "")
                .strip();
    }

    private boolean isValidIntent(String intent) {
        return intent != null && VALID_INTENTS.contains(intent);
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
