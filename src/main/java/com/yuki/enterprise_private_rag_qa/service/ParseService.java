package com.yuki.enterprise_private_rag_qa.service;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.model.DocumentVector;
import com.yuki.enterprise_private_rag_qa.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^\\s*(#{1,6}\\s+.+|第[一二三四五六七八九十百千万0-9]+[章节篇部分].*|[一二三四五六七八九十0-9]+[、.．)]\\s*.+|[（(][一二三四五六七八九十0-9]+[）)]\\s*.+)$"
    );

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired(required = false)
    private EmbeddingClient embeddingClient;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.parent-chunk-size:3000}")
    private int parentChunkSize;

    @Value("${file.parsing.child-min-chunk-size:180}")
    private int childMinChunkSize;

    @Value("${file.parsing.semantic-similarity-threshold:0.72}")
    private double semanticSimilarityThreshold;

    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    /**
     * 以流式方式解析文件，按“父块 -> 语义子块”的结构落库。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
                             String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();
        documentVectorRepository.deleteByFileMd5(fileMd5);
        logger.info("已清理旧文档分块，fileMd5: {}", fileMd5);

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            parser.parse(bufferedStream, handler, metadata, context);
            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);
        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {}%, 触发垃圾回收", String.format("%.2f", memoryUsage * 100));
            System.gc();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " +
                        String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;
        private int savedParentCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1);
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= effectiveParentChunkSize() * 2) {
                processBufferedText(false);
            }
        }

        @Override
        public void endDocument() {
            processBufferedText(true);
        }

        private void processBufferedText(boolean force) {
            if (buffer.isEmpty()) {
                return;
            }

            String textToProcess;
            if (force) {
                textToProcess = buffer.toString();
                buffer.setLength(0);
            } else {
                int cutIndex = findSafeParentBoundary(buffer);
                if (cutIndex <= 0) {
                    return;
                }
                textToProcess = buffer.substring(0, cutIndex);
                buffer.delete(0, cutIndex);
            }

            List<String> parentChunks = splitTextIntoParentChunks(textToProcess);
            for (String parentChunk : parentChunks) {
                if (parentChunk.isBlank()) {
                    continue;
                }
                savedParentCount++;
                String parentId = fileMd5 + "_p_" + savedParentCount;
                logger.debug("处理父块: parentId={}, length={}", parentId, parentChunk.length());

                List<String> childChunks = splitTextIntoChunksWithSemantics(parentChunk, effectiveChildMaxChunkSize());
                savedChunkCount = saveChildChunks(
                        fileMd5,
                        parentId,
                        parentChunk,
                        childChunks,
                        userId,
                        orgTag,
                        isPublic,
                        savedChunkCount
                );
            }
        }
    }

    private int findSafeParentBoundary(StringBuilder source) {
        int parentSize = effectiveParentChunkSize();
        if (source.length() < parentSize) {
            return -1;
        }

        int maxWindow = Math.min(source.length(), parentSize * 2);
        String candidate = source.substring(0, maxWindow);
        int minimumUsefulBoundary = Math.max(parentSize / 2, 1);

        int paragraphBoundary = Math.max(candidate.lastIndexOf("\n\n"), candidate.lastIndexOf("\r\n\r\n"));
        if (paragraphBoundary >= minimumUsefulBoundary) {
            return paragraphBoundary + 2;
        }

        int lineBoundary = Math.max(candidate.lastIndexOf('\n'), candidate.lastIndexOf('\r'));
        if (lineBoundary >= minimumUsefulBoundary) {
            return lineBoundary + 1;
        }

        return Math.min(parentSize, source.length());
    }

    private int saveChildChunks(String fileMd5, String parentId, String parentText, List<String> chunks,
                                String userId, String orgTag, boolean isPublic, int startingChunkId) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            currentChunkId++;
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setParentId(parentId);
            vector.setTextContent(chunk);
            vector.setParentTextContent(parentText);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子块到数据库，parentId: {}", chunks.size(), parentId);
        return currentChunkId;
    }

    /**
     * 父块切分：优先按标题和段落聚合，超长单元再按句子递归拆分。
     */
    private List<String> splitTextIntoParentChunks(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        int maxParentSize = effectiveParentChunkSize();
        List<String> units = splitIntoParentUnits(text);
        List<String> parents = new ArrayList<>();
        StringBuilder currentParent = new StringBuilder();

        for (String unit : units) {
            if (unit == null || unit.isBlank()) {
                continue;
            }

            String normalizedUnit = unit.trim();
            boolean title = isTitleLine(normalizedUnit);
            if (title && !currentParent.isEmpty()) {
                addIfNotBlank(parents, currentParent.toString());
                currentParent.setLength(0);
            }

            if (normalizedUnit.length() > maxParentSize) {
                if (!currentParent.isEmpty()) {
                    addIfNotBlank(parents, currentParent.toString());
                    currentParent.setLength(0);
                }
                parents.addAll(splitOversizedParentUnit(normalizedUnit, maxParentSize));
                continue;
            }

            int appendLength = currentParent.isEmpty()
                    ? normalizedUnit.length()
                    : currentParent.length() + 2 + normalizedUnit.length();
            if (appendLength > maxParentSize && !currentParent.isEmpty()) {
                addIfNotBlank(parents, currentParent.toString());
                currentParent.setLength(0);
            }

            if (!currentParent.isEmpty()) {
                currentParent.append("\n\n");
            }
            currentParent.append(normalizedUnit);
        }

        addIfNotBlank(parents, currentParent.toString());
        return parents;
    }

    private List<String> splitIntoParentUnits(String text) {
        List<String> units = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                addIfNotBlank(units, paragraph.toString());
                paragraph.setLength(0);
                continue;
            }

            if (isTitleLine(line)) {
                addIfNotBlank(units, paragraph.toString());
                paragraph.setLength(0);
                units.add(line);
                continue;
            }

            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }

        addIfNotBlank(units, paragraph.toString());
        return units;
    }

    private List<String> splitOversizedParentUnit(String unit, int maxParentSize) {
        List<String> sentences = splitIntoSentences(unit);
        if (sentences.isEmpty()) {
            return splitByCharacters(unit, maxParentSize);
        }
        return splitSentencesByLength(sentences, 1, maxParentSize);
    }

    /**
     * 子块切分：切句后计算相邻句向量相似度，低于阈值且已达最小长度则切分，超过最大长度则强制切分。
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int maxChunkSize) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        int maxSize = Math.max(1, maxChunkSize);
        int minSize = Math.min(Math.max(1, childMinChunkSize), maxSize);
        List<String> sentences = splitIntoSentences(text);
        if (sentences.isEmpty()) {
            return Collections.emptyList();
        }

        List<float[]> sentenceVectors = embedSentences(sentences);
        if (sentenceVectors.size() != sentences.size()) {
            logger.warn("句向量数量与句子数量不一致，使用长度规则降级切分。sentences={}, vectors={}",
                    sentences.size(), sentenceVectors.size());
            return splitSentencesByLength(sentences, minSize, maxSize);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(sentences.get(0));

        for (int i = 1; i < sentences.size(); i++) {
            String nextSentence = sentences.get(i);
            double similarity = cosineSimilarity(sentenceVectors.get(i - 1), sentenceVectors.get(i));
            boolean reachedMinLength = currentChunk.length() >= minSize;
            boolean semanticBreak = similarity < semanticSimilarityThreshold && reachedMinLength;
            boolean exceedsMaxLength = currentChunk.length() + nextSentence.length() > maxSize;

            if (semanticBreak || exceedsMaxLength) {
                addNormalizedChildChunk(chunks, currentChunk.toString(), maxSize);
                currentChunk.setLength(0);
                currentChunk.append(nextSentence);
            } else {
                currentChunk.append(nextSentence);
            }
        }

        addNormalizedChildChunk(chunks, currentChunk.toString(), maxSize);
        return chunks;
    }

    private List<float[]> embedSentences(List<String> sentences) {
        if (embeddingClient == null) {
            return Collections.emptyList();
        }
        try {
            return embeddingClient.embed(sentences);
        } catch (Exception e) {
            logger.warn("句向量生成失败，使用长度规则降级切分: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');

        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isTitleLine(line)) {
                sentences.add(line);
                continue;
            }

            StringBuilder sentence = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                sentence.append(c);
                if (isSentenceBoundary(c)) {
                    addIfNotBlank(sentences, sentence.toString());
                    sentence.setLength(0);
                }
            }
            addIfNotBlank(sentences, sentence.toString());
        }

        return sentences;
    }

    private List<String> splitSentencesByLength(List<String> sentences, int minSize, int maxSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence == null || sentence.isBlank()) {
                continue;
            }

            if (!currentChunk.isEmpty() && currentChunk.length() + sentence.length() > maxSize) {
                addNormalizedChildChunk(chunks, currentChunk.toString(), maxSize);
                currentChunk.setLength(0);
            }

            if (currentChunk.isEmpty() && sentence.length() > maxSize) {
                addNormalizedChildChunk(chunks, sentence, maxSize);
                continue;
            }

            currentChunk.append(sentence);
        }

        addNormalizedChildChunk(chunks, currentChunk.toString(), maxSize);
        return chunks;
    }

    private void addNormalizedChildChunk(List<String> chunks, String chunk, int maxSize) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        String normalized = chunk.trim();
        if (normalized.length() <= maxSize) {
            chunks.add(normalized);
            return;
        }
        splitLongSentence(normalized, maxSize).stream()
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .forEach(chunks::add);
    }

    private void addIfNotBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private boolean isTitleLine(String line) {
        return line != null && line.length() <= 80 && TITLE_PATTERN.matcher(line).matches();
    }

    private boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '.' || c == '!' || c == '?' || c == ';';
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0d;
        }

        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private int effectiveParentChunkSize() {
        return Math.max(parentChunkSize, Math.max(chunkSize, 1));
    }

    private int effectiveChildMaxChunkSize() {
        return Math.max(chunkSize, 1);
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割。
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        try {
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;

                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }

            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}",
                    sentence.length(), termList.size(), chunks.size());
        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符切分作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
        }

        return chunks;
    }

    /**
     * 备用方案：按字符切分。
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
