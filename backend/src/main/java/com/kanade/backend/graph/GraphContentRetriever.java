package com.kanade.backend.graph;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱内容检索器——将 Neo4j 图谱查询结果包装为 LangChain4j Content。
 * 实现 ContentRetriever 接口，可无缝接入现有 RAG 管道。
 *
 * 流程：用户查询 → 提取关键词 → 搜索实体 → 扩展子图 → 文本化 → Content 列表
 *
 * @author kanade
 */
@Slf4j
public class GraphContentRetriever implements ContentRetriever {

    private final GraphCrudService graphCrudService;
    private final CypherTemplateEngine cypherEngine;
    private final int maxResults;
    private final Long userId;

    /**
     * @param graphCrudService 图谱 CRUD 服务
     * @param cypherEngine     Cypher 模板引擎
     * @param maxResults       最大返回结果数
     * @param userId           用户 ID（多租户隔离）
     */
    public GraphContentRetriever(GraphCrudService graphCrudService,
                                  CypherTemplateEngine cypherEngine,
                                  int maxResults,
                                  Long userId) {
        this.graphCrudService = graphCrudService;
        this.cypherEngine = cypherEngine;
        this.maxResults = maxResults;
        this.userId = userId;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return Collections.emptyList();
        }

        try {
            String queryText = query.text();
            log.info("🔍 [图谱检索] query='{}', userId={}", queryText, userId);

            // 阶段 1：搜索匹配的实体
            List<Map<String, Object>> matchedEntities = searchEntities(queryText);
            if (matchedEntities.isEmpty()) {
                log.info("🔍 [图谱检索] 未找到匹配实体");
                return Collections.emptyList();
            }

            // 阶段 2：对每个匹配实体扩展子图
            List<Content> contents = new ArrayList<>();
            for (Map<String, Object> entity : matchedEntities) {
                String entityName = (String) entity.get("name");
                Map<String, Object> subgraph = graphCrudService.expandSubgraph(entityName, userId, 1);
                if (!subgraph.isEmpty()) {
                    String contextText = subgraphToContext(entityName, subgraph);
                    contents.add(Content.from(contextText));
                }
            }

            log.info("🔍 [图谱检索完成] 匹配实体={}, 返回内容={}", matchedEntities.size(), contents.size());
            return contents.stream().limit(maxResults).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ [图谱检索失败] query='{}', error={}", query.text(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从查询文本中搜索匹配的实体。
     * 策略：先用整个查询作为关键词搜索，若无结果则拆分为词逐词搜索。
     */
    private List<Map<String, Object>> searchEntities(String queryText) {
        // 策略 1：完整查询
        List<Map<String, Object>> results = graphCrudService.searchEntities(queryText, userId, 5)
            .stream()
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getName());
                m.put("type", e.getType());
                m.put("entityId", e.getEntityId());
                return m;
            })
            .collect(Collectors.toList());

        if (!results.isEmpty()) {
            return results;
        }

        // 策略 2：按逗号、空格拆分关键词
        String[] keywords = queryText.split("[,，\\s]+");
        Set<Map<String, Object>> allResults = new LinkedHashSet<>();
        for (String kw : keywords) {
            if (kw.length() < 2) continue;
            List<Map<String, Object>> kwResults = graphCrudService.searchEntities(kw, userId, 3)
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getName());
                    m.put("type", e.getType());
                    m.put("entityId", e.getEntityId());
                    return m;
                })
                .collect(Collectors.toList());
            allResults.addAll(kwResults);
        }

        return new ArrayList<>(allResults).stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 将子图结构文本化，供 LLM 理解。
     */
    private String subgraphToContext(String seedEntity, Map<String, Object> subgraph) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱信息 - 相关实体：").append(seedEntity).append("】\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) subgraph.getOrDefault("nodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) subgraph.getOrDefault("edges", List.of());

        if (!nodes.isEmpty()) {
            sb.append("关联实体：");
            for (Map<String, Object> node : nodes) {
                sb.append(node.get("name")).append("(").append(node.get("type")).append("), ");
            }
            sb.setLength(sb.length() - 2);
            sb.append("\n");
        }

        if (!edges.isEmpty()) {
            sb.append("关系：");
            for (Map<String, Object> edge : edges) {
                sb.append(edge.get("type")).append(", ");
            }
            sb.setLength(sb.length() - 2);
            sb.append("\n");
        }

        return sb.toString();
    }
}
