package com.yuki.enterprise_private_rag_qa.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Query 字符级规范化。
 * 全角转半角、Unicode 规范化、标点统一、多余空格压缩、不可见字符清理。
 * 纯代码实现，无 LLM 调用。
 */
@Component
public class QueryNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(QueryNormalizer.class);
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public String normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return rawQuery;
        }

        String query = rawQuery;

        // 1. 清理不可见控制字符（保留换行、回车、制表符）
        query = CONTROL_CHARS.matcher(query).replaceAll("");

        // 2. Unicode NFKC 规范化（完成大部分全角→半角转换）
        query = Normalizer.normalize(query, Normalizer.Form.NFKC);

        // 3. 标点归一化：统一中英文标点
        query = query.replace('？', '?');   // ？→ ?
        query = query.replace('，', ',');   // ，→ ,
        query = query.replace('：', ':');   // ：→ :
        query = query.replace('；', ';');   // ；→ ;
        query = query.replace('（', '(');    // （→ (
        query = query.replace('）', ')');    // ）→ )
        query = query.replace('、', ',');    // 、→ ,
        query = query.replace('“', '"');    // "→ "
        query = query.replace('”', '"');    // "→ "
        query = query.replace('‘', '\'');   // '→ '
        query = query.replace('’', '\'');   // '→ '

        // 4. 多余空格压缩
        query = MULTI_SPACE.matcher(query).replaceAll(" ").trim();

        logger.debug("Normalization: [{}] → [{}]", rawQuery, query);
        return query;
    }
}
