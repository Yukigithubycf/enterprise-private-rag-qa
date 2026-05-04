package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.client.RagLlmClient;
import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentDetectorTest {

    @Mock
    private RagLlmClient llmClient;

    private IntentDetector detector;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        // Default: LLM disabled
        ragProperties.getIntent().setEnableLlm(false);
        detector = new IntentDetector(llmClient, ragProperties);
    }

    // === Phase 1 规则匹配测试（LLM 未启用时使用规则） ===

    @Test
    void shouldDetectReasonAnalysis() {
        assertEquals("原因分析", detector.detect("为什么RAG系统需要多路召回"));
        assertEquals("原因分析", detector.detect("Query Rewrite为何会导致召回结果跑偏"));
    }

    @Test
    void shouldDetectImplementationMethod() {
        assertEquals("实现方法", detector.detect("如何实现Query Rewrite"));
        assertEquals("实现方法", detector.detect("怎么实现RAG检索链路"));
        assertEquals("实现方法", detector.detect("Query Rewrite的代码实现步骤"));
    }

    @Test
    void shouldDetectConceptExplanation() {
        assertEquals("概念解释", detector.detect("Query Rewrite是什么"));
        assertEquals("概念解释", detector.detect("介绍一下HyDE的概念"));
    }

    @Test
    void shouldDetectComparison() {
        assertEquals("对比分析", detector.detect("Query Rewrite和HyDE有什么区别"));
        assertEquals("对比分析", detector.detect("RRF和MMR的优缺点对比"));
    }

    @Test
    void shouldDetectTroubleshooting() {
        assertEquals("故障排查", detector.detect("RAG系统报错了怎么解决"));
        assertEquals("故障排查", detector.detect("ES索引失败异常"));
    }

    @Test
    void shouldDetectLocatePosition() {
        assertEquals("查找定位", detector.detect("这个函数在哪里定义"));
    }

    @Test
    void shouldDefaultToGeneralQa() {
        assertEquals("通用问答", detector.detect("hello world"));
        assertEquals("通用问答", detector.detect(""));
    }

    // === Phase 6.1: LLM 意图识别测试 ===

    @Test
    void shouldUseLlmWhenEnabled() {
        ragProperties.getIntent().setEnableLlm(true);
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("原因分析");

        String intent = detector.detect("为什么我的RAG召回效果不好");

        assertEquals("原因分析", intent);
    }

    @Test
    void shouldFallbackToRulesWhenLlmFails() {
        ragProperties.getIntent().setEnableLlm(true);
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("LLM timeout"));

        // Should fall back to rule-based detection
        String intent = detector.detect("为什么RAG系统需要多路召回");

        assertEquals("原因分析", intent);
    }

    @Test
    void shouldFallbackToRulesWhenLlmReturnsInvalidIntent() {
        ragProperties.getIntent().setEnableLlm(true);
        when(llmClient.chatSync(isNull(), anyString(), anyDouble()))
                .thenReturn("无效意图");

        // Should fall back to rule-based detection
        String intent = detector.detect("为什么RAG系统需要多路召回");

        assertEquals("原因分析", intent);
    }
}
