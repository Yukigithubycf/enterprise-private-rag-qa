package com.yuki.enterprise_private_rag_qa.service.rag;

import com.yuki.enterprise_private_rag_qa.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionGateTest {

    private ReflectionGate gate;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        gate = new ReflectionGate(ragProperties);
    }

    @Test
    void shouldDisableWhenTopKSmall() {
        assertFalse(gate.shouldReflect("实现方法", 2));
        assertFalse(gate.shouldReflect("实现方法", 1));
    }

    @Test
    void shouldDisableForPrecisionIntent() {
        assertFalse(gate.shouldReflect("查找定位", 8));
    }

    @Test
    void shouldEnableForMultiAngleIntent() {
        assertTrue(gate.shouldReflect("实现方法", 8));
        assertTrue(gate.shouldReflect("原因分析", 6));
        assertTrue(gate.shouldReflect("对比分析", 8));
        assertTrue(gate.shouldReflect("总结归纳", 8));
    }

    @Test
    void shouldDisableForAmbiguousIntent() {
        assertFalse(gate.shouldReflect("通用问答", 8));
    }

    @Test
    void shouldRespectGlobalConfig() {
        ragProperties.getPipeline().setEnableReflection(false);
        assertFalse(gate.shouldReflect("实现方法", 8));
    }

    @Test
    void shouldReturnMaxReflectionRounds() {
        assertEquals(1, gate.getMaxReflectionRounds());

        ragProperties.getPipeline().setMaxReflectionRounds(2);
        assertEquals(2, gate.getMaxReflectionRounds());
    }
}
