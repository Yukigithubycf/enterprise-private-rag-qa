package com.yuki.enterprise_private_rag_qa.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.yuki.enterprise_private_rag_qa.client.EmbeddingClient;
import com.yuki.enterprise_private_rag_qa.entity.EsDocument;
import com.yuki.enterprise_private_rag_qa.entity.SearchResult;
import com.yuki.enterprise_private_rag_qa.exception.CustomException;
import com.yuki.enterprise_private_rag_qa.model.FileUpload;
import com.yuki.enterprise_private_rag_qa.model.User;
import com.yuki.enterprise_private_rag_qa.repository.FileUploadRepository;
import com.yuki.enterprise_private_rag_qa.repository.UserRepository;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String INDEX_NAME = "knowledge_base";
    private static final int RECALL_MULTIPLIER = 30;
    private static final int RRF_K = 60;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);
        
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            // 这里主要是为了做一个归一化操作，因为ES存储的是用户表的主键ID，而传进来的userId有可能为用户名，所以说需要基于用户名将用户表的主键ID查出来
            // 用户的数据库ID和用户ID是一样的
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            logger.debug("向量生成成功，开始执行混合搜索 KNN");

            Query permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);
            int recallK = getRecallK(topK);
            List<SearchResult> semanticResults = safeSemanticSearch(queryVector, permissionFilter, recallK);
            List<SearchResult> keywordResults = safeKeywordSearch(query, permissionFilter, recallK, true);
            List<SearchResult> fusedChildResults = rrfFuse(semanticResults, keywordResults, recallK);
            List<SearchResult> results = aggregateByParent(fusedChildResults, topK);

            logger.debug("RRF融合和父块聚合完成，语义召回: {}, 关键词召回: {}, 父块结果: {}",
                    semanticResults.size(), keywordResults.size(), results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(INDEX_NAME)
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(buildPermissionFilter(userDbId, userEffectiveTags))
                            )
                    )
                    .minScore(0.3d)
                    .size(getRecallK(topK)),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> childResults = response.hits().hits().stream()
                    .map(this::toSearchResult)
                    .toList();
            List<SearchResult> results = aggregateByParent(childResults, topK);

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            Query publicOnlyFilter = buildPublicOnlyFilter();
            int recallK = getRecallK(topK);
            List<SearchResult> semanticResults = safeSemanticSearch(queryVector, publicOnlyFilter, recallK);
            List<SearchResult> keywordResults = safeKeywordSearch(query, publicOnlyFilter, recallK, false);
            List<SearchResult> fusedChildResults = rrfFuse(semanticResults, keywordResults, recallK);
            List<SearchResult> results = aggregateByParent(fusedChildResults, topK);
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index(INDEX_NAME)
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(ma -> ma
                                .field("textContent")
                                .query(query)))
                        .filter(buildPublicOnlyFilter())))
                .size(getRecallK(topK)),
                EsDocument.class
        );

        List<SearchResult> childResults = response.hits().hits().stream()
                .map(this::toSearchResult)
                .toList();
        List<SearchResult> results = aggregateByParent(childResults, topK);
        attachFileNames(results);
        return results;
    }

    // ========================================================================
    // Public search methods for MultiRouteRetriever (Phase 2)
    // ========================================================================

    /**
     * 通过文本进行语义检索（自动 embedding）。
     * 供 MultiRouteRetriever 调用。
     */
    public List<SearchResult> semanticSearchByText(String text, Query permissionFilter, int topK) {
        List<Float> vector = embedToVectorList(text);
        if (vector == null) {
            logger.warn("语义检索 embedding 失败，返回空列表: {}", text);
            return Collections.emptyList();
        }
        return safeSemanticSearch(vector, permissionFilter, getRecallK(topK));
    }

    /**
     * 通过文本进行关键词检索。
     * 供 MultiRouteRetriever 调用。
     */
    public List<SearchResult> keywordSearchByText(String query, Query permissionFilter, int topK, boolean minScore) {
        return safeKeywordSearch(query, permissionFilter, getRecallK(topK), minScore);
    }

    /**
     * 构建带权限的 ES 查询 filter。
     * 供 MultiRouteRetriever 获取统一的权限过滤条件。
     */
    public Query buildPermissionFilter(String userId) {
        List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
        String userDbId = getUserDbId(userId);
        return buildPermissionFilter(userDbId, userEffectiveTags);
    }

    // ========================================================================
    // Private search methods
    // ========================================================================

    private List<SearchResult> safeSemanticSearch(List<Float> queryVector, Query permissionFilter, int recallK) {
        try {
            return semanticSearch(queryVector, permissionFilter, recallK);
        } catch (Exception e) {
            logger.warn("语义检索失败，本轮将仅使用关键词召回参与融合", e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> safeKeywordSearch(String query, Query permissionFilter, int recallK, boolean minScore) {
        try {
            return keywordSearch(query, permissionFilter, recallK, minScore);
        } catch (Exception e) {
            logger.warn("关键词检索失败，本轮将仅使用语义召回参与融合", e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> semanticSearch(List<Float> queryVector, Query permissionFilter, int recallK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index(INDEX_NAME)
                        .knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                                .filter(permissionFilter)
                        )
                        .size(recallK),
                EsDocument.class
        );

        logger.debug("语义检索完成，命中数量: {}, 最大分数: {}",
                response.hits().total() == null ? 0 : response.hits().total().value(), response.hits().maxScore());
        return response.hits().hits().stream()
                .map(this::toSearchResult)
                .toList();
    }

    private List<SearchResult> keywordSearch(String query, Query permissionFilter, int recallK, boolean minScore) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> {
                    s.index(INDEX_NAME)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma
                                            .field("textContent")
                                            .query(query)))
                                    .filter(permissionFilter)))
                            .size(recallK);
                    if (minScore) {
                        s.minScore(0.3d);
                    }
                    return s;
                },
                EsDocument.class
        );

        logger.debug("关键词检索完成，命中数量: {}, 最大分数: {}",
                response.hits().total() == null ? 0 : response.hits().total().value(), response.hits().maxScore());
        return response.hits().hits().stream()
                .map(this::toSearchResult)
                .toList();
    }

    private List<SearchResult> rrfFuse(List<SearchResult> semanticResults, List<SearchResult> keywordResults, int topK) {
        Map<String, RrfEntry> fused = new LinkedHashMap<>();
        addRrfScores(fused, semanticResults);
        addRrfScores(fused, keywordResults);

        return fused.values().stream()
                .sorted(Comparator
                        .comparingDouble(RrfEntry::getRrfScore).reversed()
                        .thenComparing(Comparator.comparingDouble(RrfEntry::getBestOriginalScore).reversed()))
                .limit(Math.max(topK, 1))
                .map(entry -> {
                    SearchResult result = entry.getResult();
                    result.setScore(entry.getRrfScore());
                    return result;
                })
                .toList();
    }

    private void addRrfScores(Map<String, RrfEntry> fused, List<SearchResult> rankedResults) {
        Set<String> seenInCurrentRanking = new HashSet<>();
        for (int i = 0; i < rankedResults.size(); i++) {
            SearchResult result = rankedResults.get(i);
            String key = buildResultKey(result);
            if (!seenInCurrentRanking.add(key)) {
                continue;
            }

            int rank = i + 1;
            double rrfScore = 1.0d / (RRF_K + rank);
            double originalScore = result.getScore() == null ? 0.0d : result.getScore();
            RrfEntry entry = fused.computeIfAbsent(key, ignored -> new RrfEntry(copyResult(result)));
            entry.addScore(rrfScore, originalScore);
        }
    }

    private SearchResult copyResult(SearchResult source) {
        return new SearchResult(
                source.getFileMd5(),
                source.getChunkId(),
                source.getParentId(),
                source.getTextContent(),
                source.getParentTextContent(),
                source.getScore(),
                source.getUserId(),
                source.getOrgTag(),
                Boolean.TRUE.equals(source.getIsPublic()),
                source.getFileName()
        );
    }

    private String buildResultKey(SearchResult result) {
        return String.format("%s#%s", result.getFileMd5(), result.getChunkId());
    }

    private List<SearchResult> aggregateByParent(List<SearchResult> childResults, int topK) {
        Map<String, SearchResult> parentResults = new LinkedHashMap<>();
        for (SearchResult childResult : childResults) {
            String parentKey = buildParentKey(childResult);
            SearchResult parentResult = toParentResult(childResult);
            SearchResult existing = parentResults.get(parentKey);
            if (existing == null || scoreOf(parentResult) > scoreOf(existing)) {
                parentResults.put(parentKey, parentResult);
            } else if (isBlank(existing.getParentTextContent()) && !isBlank(parentResult.getParentTextContent())) {
                existing.setParentTextContent(parentResult.getParentTextContent());
                existing.setTextContent(parentResult.getParentTextContent());
            }
        }

        return parentResults.values().stream()
                .sorted(Comparator.comparingDouble(this::scoreOf).reversed())
                .limit(Math.max(topK, 1))
                .toList();
    }

    private SearchResult toParentResult(SearchResult childResult) {
        SearchResult result = copyResult(childResult);
        String parentText = resolveParentText(childResult);
        result.setTextContent(parentText);
        result.setParentTextContent(parentText);
        return result;
    }

    private String resolveParentText(SearchResult result) {
        if (!isBlank(result.getParentTextContent())) {
            return result.getParentTextContent();
        }
        return result.getTextContent();
    }

    private String buildParentKey(SearchResult result) {
        if (!isBlank(result.getParentId())) {
            return String.format("%s#%s", result.getFileMd5(), result.getParentId());
        }
        return buildResultKey(result);
    }

    private double scoreOf(SearchResult result) {
        return result.getScore() == null ? 0.0d : result.getScore();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value(userDbId)));
            appendPublicAccessShoulds(b);
            userEffectiveTags.forEach(tag ->
                    b.should(s -> s.term(t -> t.field("orgTag").value(tag)))
            );
            return b.minimumShouldMatch("1");
        }));
    }

    private Query buildPublicOnlyFilter() {
        return Query.of(q -> q.bool(b -> {
            appendPublicAccessShoulds(b);
            return b.minimumShouldMatch("1");
        }));
    }

    private void appendPublicAccessShoulds(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b) {
        // 兼容 Java boolean isPublic 序列化成 public 以及 mapping 中定义的 isPublic 两种字段名。
        b.should(s -> s.term(t -> t.field("public").value(true)));
        b.should(s -> s.term(t -> t.field("isPublic").value(true)));
    }

    private SearchResult toSearchResult(Hit<EsDocument> hit) {
        EsDocument source = hit.source();
        if (source == null) {
            throw new IllegalStateException("Elasticsearch hit source is null");
        }
        logger.debug("检索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}",
                source.getFileMd5(), source.getChunkId(), hit.score(),
                source.getTextContent().substring(0, Math.min(50, source.getTextContent().length())));
        return new SearchResult(
                source.getFileMd5(),
                source.getChunkId(),
                source.getParentId(),
                source.getTextContent(),
                source.getParentTextContent(),
                hit.score(),
                source.getUserId(),
                source.getOrgTag(),
                source.isPublic(),
                null
        );
    }

    private int getRecallK(int topK) {
        return Math.max(Math.max(topK, 1) * RECALL_MULTIPLIER, 1);
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    private static final class RrfEntry {
        private final SearchResult result;
        private double rrfScore;
        private double bestOriginalScore;

        private RrfEntry(SearchResult result) {
            this.result = result;
            this.bestOriginalScore = result.getScore() == null ? 0.0d : result.getScore();
        }

        private void addScore(double score, double originalScore) {
            this.rrfScore += score;
            this.bestOriginalScore = Math.max(this.bestOriginalScore, originalScore);
        }

        private SearchResult getResult() {
            return result;
        }

        private double getRrfScore() {
            return rrfScore;
        }

        private double getBestOriginalScore() {
            return bestOriginalScore;
        }
    }
}
