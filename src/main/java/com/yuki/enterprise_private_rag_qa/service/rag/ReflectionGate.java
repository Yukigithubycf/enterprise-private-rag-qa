package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import com.yuki.enterprise_private_rag_qa.model.query.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reflection 门控判断。
 * 与 MMR Gate 类似，Reflection 并非所有问题都需要。
 *
 * 开启条件：
 *   - intent 属于多角度型（实现方法、方案设计、总结归纳、对比分析、原因分析等）
 *   - final_top_k > 2
 *
 * 关闭条件：
 *   - intent 属于精确定位型（查找定位、参数查询、公式查询等）
 *   - final_top_k <= 2
 *   - pipeline 配置中 enable-reflection = false
 */
@Component
public class ReflectionGate {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionGate.class);

    private final RagProperties ragProperties;

    public ReflectionGate(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 判断是否启用 Reflection 检查。
     *
     * @param intent    用户问题意图
     * @param finalTopK 最终要返回的 chunk 数量
     * @return true 表示启用 Reflection
     */
    public boolean shouldReflect(String intent, int finalTopK) {
        // 全局开关
        if (!ragProperties.getPipeline().isEnableReflection()) {
            logger.debug("Reflection disabled: global config enable-reflection=false");
            return false;
        }

        // topK 过小不启用
        if (finalTopK <= 2) {
            logger.debug("Reflection disabled: final_top_k={} <= 2", finalTopK);
            return false;
        }

        // 精确事实/定位型问题不启用
        if (Intent.isPrecisionIntent(intent)) {
            logger.debug("Reflection disabled: intent={} is precision type", intent);
            return false;
        }

        // 多角度型问题启用
        if (Intent.isMultiAngleIntent(intent)) {
            logger.debug("Reflection enabled: intent={} needs multi-angle context", intent);
            return true;
        }

        // 意图不明确 → 默认不启用（避免增加延迟和成本）
        logger.debug("Reflection disabled: intent={} is ambiguous, defaulting to off", intent);
        return false;
    }

    /**
     * 获取最大反思轮数。
     */
    public int getMaxReflectionRounds() {
        return ragProperties.getPipeline().getMaxReflectionRounds();
    }
}
