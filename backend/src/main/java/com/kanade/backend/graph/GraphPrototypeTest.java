package com.kanade.backend.graph;

import com.kanade.backend.graph.model.GraphEntity;
import com.kanade.backend.graph.model.GraphRelation;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱原型验证——不依赖 Neo4j，使用内存 Map 模拟端到端链路。
 * 验证：文本 → LLM JSON（模拟）→ 三元组解析 → 实体对齐 → 图查询。
 *
 * 可在 Neo4j 尚未部署时先行验证 Prompt 设计和 JSON 解析逻辑。
 *
 * @author kanade
 */
@Slf4j
public class GraphPrototypeTest {

    private static final Gson GSON = new Gson();

    /**
     * 模拟 LLM 返回的 JSON 三元组抽取结果。
     */
    private static final String MOCK_LLM_RESPONSE = """
        {
            "entities": [
                {"entityId": "e1", "name": "机器学习", "type": "Concept", "sourceDocId": "1"},
                {"entityId": "e2", "name": "监督学习", "type": "Concept", "sourceDocId": "1"},
                {"entityId": "e3", "name": "神经网络", "type": "Concept", "sourceDocId": "1"},
                {"entityId": "e4", "name": "深度学习", "type": "Concept", "sourceDocId": "1"},
                {"entityId": "e5", "name": "吴恩达", "type": "Person", "sourceDocId": "1"}
            ],
            "relations": [
                {"fromEntityId": "e1", "toEntityId": "e2", "type": "INCLUDES"},
                {"fromEntityId": "e2", "toEntityId": "e3", "type": "USES"},
                {"fromEntityId": "e1", "toEntityId": "e4", "type": "RELATED_TO"},
                {"fromEntityId": "e3", "toEntityId": "e4", "type": "RELATED_TO"},
                {"fromEntityId": "e5", "toEntityId": "e1", "type": "TEACHES"}
            ]
        }
        """;

    // 内存图存储（Key: entityId, Value: GraphEntity）
    private final Map<String, GraphEntity> entityStore = new LinkedHashMap<>();
    // 内存关系存储
    private final List<GraphRelation> relationStore = new ArrayList<>();

    public static void main(String[] args) {
        GraphPrototypeTest test = new GraphPrototypeTest();
        test.run();
    }

    public void run() {
        log.info("══════════════════════════════════════");
        log.info("🚀 图谱原型验证开始（内存模式）");
        log.info("══════════════════════════════════════");

        // 阶段 1：模拟 LLM JSON 解析
        log.info("\n📋 阶段 1：解析 LLM 返回的 JSON 三元组");
        LllmResponse response = parseLllmResponse(MOCK_LLM_RESPONSE);
        log.info("  解析到实体: {} 个, 关系: {} 个",
            response.entities.size(), response.relations.size());

        // 阶段 2：实体写入（模拟）
        log.info("\n📝 阶段 2：实体写入图存储");
        for (LllmEntity e : response.entities) {
            GraphEntity entity = GraphEntity.builder()
                .entityId(e.entityId)
                .name(e.name)
                .type(e.type)
                .userId(1L)
                .sourceDocIds(e.sourceDocId)
                .build();
            entityStore.put(e.entityId, entity);
            log.info("  写入实体: [{}] {} ({})", e.entityId, e.name, e.type);
        }

        // 阶段 3：关系写入（模拟）
        log.info("\n🔗 阶段 3：关系写入图存储");
        for (LllmRelation r : response.relations) {
            GraphRelation relation = GraphRelation.builder()
                .fromEntityId(r.fromEntityId)
                .toEntityId(r.toEntityId)
                .type(r.type)
                .userId(1L)
                .sourceDocId("1")
                .build();
            relationStore.add(relation);
            log.info("  写入关系: [{}] -[{}]-> [{}]",
                r.fromEntityId, r.type, r.toEntityId);
        }

        // 阶段 4：实体对齐（模拟——同名同类型合并）
        log.info("\n🔄 阶段 4：实体对齐");
        int merged = alignEntities();
        log.info("  合并实体: {} 个（去重后总数: {}）", merged, entityStore.size());

        // 阶段 5：子图查询（模拟）
        log.info("\n🔍 阶段 5：子图查询");
        String seedEntity = "机器学习";
        Map<String, Object> subgraph = querySubgraph(seedEntity);
        log.info("  种子实体: {}", seedEntity);
        log.info("  子图节点: {}", subgraph.get("nodes"));
        log.info("  子图边: {}", subgraph.get("edges"));

        // 阶段 6：子图文本化（GraphContentInjector 的输入格式预览）
        log.info("\n📄 阶段 6：子图结构文本化（供 LLM 注入用）");
        String context = subgraphToContext(subgraph);
        log.info("  注入上下文:\n{}", context);

        log.info("\n══════════════════════════════════════");
        log.info("✅ 原型验证通过——端到端链路走通");
        log.info("══════════════════════════════════════");
    }

    /**
     * 解析模拟的 LLM JSON 响应。
     */
    private LllmResponse parseLllmResponse(String json) {
        Type type = new TypeToken<LllmResponse>() {}.getType();
        return GSON.fromJson(json, type);
    }

    /**
     * 实体对齐——同名同类型合并，冲突标记。
     */
    private int alignEntities() {
        int merged = 0;
        Map<String, GraphEntity> unified = new LinkedHashMap<>();

        for (GraphEntity entity : entityStore.values()) {
            String key = entity.getName() + "::" + entity.getType();
            if (unified.containsKey(key)) {
                // 合并源文档 ID
                GraphEntity existing = unified.get(key);
                String mergedDocIds = existing.getSourceDocIds() + "," + entity.getSourceDocIds();
                existing.setSourceDocIds(
                    Arrays.stream(mergedDocIds.split(","))
                        .distinct()
                        .collect(Collectors.joining(","))
                );
                merged++;
                log.debug("  合并: {} → {}", entity.getEntityId(), existing.getEntityId());
            } else {
                unified.put(key, entity);
            }
        }

        entityStore.clear();
        entityStore.putAll(unified);
        return merged;
    }

    /**
     * 子图查询——从种子实体出发扩展 1 跳。
     */
    private Map<String, Object> querySubgraph(String seedName) {
        // 找到种子实体
        List<GraphEntity> seeds = entityStore.values().stream()
            .filter(e -> e.getName().equals(seedName))
            .collect(Collectors.toList());

        Set<String> nodeIds = new LinkedHashSet<>();
        List<String> edges = new ArrayList<>();

        for (GraphEntity seed : seeds) {
            nodeIds.add(seed.getName() + "(" + seed.getType() + ")");

            // 扩展 1 跳
            for (GraphRelation rel : relationStore) {
                if (rel.getFromEntityId().equals(seed.getEntityId())) {
                    GraphEntity target = entityStore.get(rel.getToEntityId());
                    if (target != null) {
                        nodeIds.add(target.getName() + "(" + target.getType() + ")");
                        edges.add(seed.getName() + " -[" + rel.getType() + "]-> " + target.getName());
                    }
                }
            }
        }

        return Map.of("nodes", new ArrayList<>(nodeIds), "edges", edges);
    }

    /**
     * 将子图结构文本化，供 GraphContentInjector 注入 LLM Prompt。
     */
    private String subgraphToContext(Map<String, Object> subgraph) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱子图信息】\n");
        sb.append("节点：\n");

        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) subgraph.get("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(nodes.get(i)).append("\n");
        }

        sb.append("\n关系：\n");
        @SuppressWarnings("unchecked")
        List<String> edges = (List<String>) subgraph.get("edges");
        for (int i = 0; i < edges.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(edges.get(i)).append("\n");
        }

        return sb.toString();
    }

    // ==================== 内部类（模拟 LLM JSON 结构） ====================

    static class LllmResponse {
        List<LllmEntity> entities;
        List<LllmRelation> relations;
    }

    static class LllmEntity {
        String entityId;
        String name;
        String type;
        String sourceDocId;
    }

    static class LllmRelation {
        String fromEntityId;
        String toEntityId;
        String type;
    }
}
