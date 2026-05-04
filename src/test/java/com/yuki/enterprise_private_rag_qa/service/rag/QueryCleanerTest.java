package com.yuki.enterprise_private_rag_qa.service.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryCleanerTest {

    private final QueryCleaner cleaner = new QueryCleaner();

    @Test
    void shouldRemoveNoiseWords() {
        String input = "请问一下，Query Rewrite 为什么会让召回结果跑偏呢？";
        String result = cleaner.clean(input);
        assertFalse(result.contains("请问"));
        assertFalse(result.contains("一下"));
        assertFalse(result.contains("呢"));
    }

    @Test
    void shouldReplaceIntentKeywords() {
        String input = "为什么RAG系统需要多路召回";
        String result = cleaner.clean(input);
        assertTrue(result.contains("原因"));
        assertFalse(result.contains("为什么"));
    }

    @Test
    void shouldReplaceHowKeyword() {
        String input = "如何实现Query Rewrite";
        String result = cleaner.clean(input);
        assertTrue(result.contains("实现"));
        assertFalse(result.contains("如何"));
    }

    @Test
    void shouldFallbackWhenEmptied() {
        String input = "呢啊吗";
        String result = cleaner.clean(input);
        assertEquals(input, result);
    }

    @Test
    void shouldHandleNullInput() {
        assertNull(cleaner.clean(null));
    }
}
