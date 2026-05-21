package com.kanade.backend.graph;

import com.kanade.backend.graph.model.GraphEntity;
import com.kanade.backend.graph.model.GraphRelation;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.neo4j.driver.Values.parameters;

/**
 * 图谱 CRUD 服务——封装 Neo4j 节点/边的创建、查询、删除。
 * 所有操作强制携带 userId 实现多租户隔离。
 *
 * @author kanade
 */
@Slf4j
@Service
public class GraphCrudService {

    private final Neo4jSessionFactory sessionFactory;

    public GraphCrudService(Neo4jSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    // ==================== 节点操作 ====================

    /**
     * 创建或合并实体节点（按 entityId + userId 去重）。
     */
    public GraphEntity mergeEntity(GraphEntity entity) {
        long now = Instant.now().toEpochMilli();
        if (entity.getCreatedAt() == null) entity.setCreatedAt(now);
        if (entity.getUpdatedAt() == null) entity.setUpdatedAt(now);

        return sessionFactory.withWriteSession(session -> {
            Result result = session.run(
                """
                MERGE (e:Entity {entityId: $entityId, userId: $userId})
                ON CREATE SET
                    e.name = $name,
                    e.type = $type,
                    e.sourceDocIds = $sourceDocIds,
                    e.properties = $properties,
                    e.createdAt = $createdAt,
                    e.updatedAt = $updatedAt
                ON MATCH SET
                    e.name = $name,
                    e.type = $type,
                    e.sourceDocIds = $sourceDocIds,
                    e.properties = $properties,
                    e.updatedAt = $updatedAt
                RETURN e, id(e) AS neo4jId
                """,
                parameters(
                    "entityId", entity.getEntityId(),
                    "userId", entity.getUserId(),
                    "name", entity.getName(),
                    "type", entity.getType(),
                    "sourceDocIds", entity.getSourceDocIds() != null ? entity.getSourceDocIds() : "",
                    "properties", entity.getProperties() != null ? entity.getProperties() : Map.of(),
                    "createdAt", entity.getCreatedAt(),
                    "updatedAt", entity.getUpdatedAt()
                )
            );

            if (result.hasNext()) {
                Record record = result.next();
                entity.setNeo4jId(record.get("neo4jId").asLong());
                log.debug("✅ [图谱] 实体已合并: entityId={}, name={}", entity.getEntityId(), entity.getName());
            }
            return entity;
        });
    }

    /**
     * 批量合并实体节点（UNWIND 批量写入）。
     */
    public List<GraphEntity> mergeEntitiesBatch(List<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();

        long now = Instant.now().toEpochMilli();
        entities.forEach(e -> {
            if (e.getCreatedAt() == null) e.setCreatedAt(now);
            if (e.getUpdatedAt() == null) e.setUpdatedAt(now);
        });

        List<Map<String, Object>> batch = entities.stream()
                .map(e -> Map.<String, Object>of(
                    "entityId", e.getEntityId(),
                    "userId", e.getUserId(),
                    "name", e.getName(),
                    "type", e.getType(),
                    "sourceDocIds", e.getSourceDocIds() != null ? e.getSourceDocIds() : "",
                    "properties", e.getProperties() != null ? e.getProperties() : Map.of(),
                    "createdAt", e.getCreatedAt(),
                    "updatedAt", e.getUpdatedAt()
                ))
                .collect(Collectors.toList());

        sessionFactory.withWriteSession(session -> {
            session.run(
                """
                UNWIND $batch AS row
                MERGE (e:Entity {entityId: row.entityId, userId: row.userId})
                ON CREATE SET
                    e.name = row.name,
                    e.type = row.type,
                    e.sourceDocIds = row.sourceDocIds,
                    e.properties = row.properties,
                    e.createdAt = row.createdAt,
                    e.updatedAt = row.updatedAt
                ON MATCH SET
                    e.name = row.name,
                    e.type = row.type,
                    e.sourceDocIds = row.sourceDocIds,
                    e.properties = row.properties,
                    e.updatedAt = row.updatedAt
                """,
                parameters("batch", batch)
            );
        });

        log.info("✅ [图谱] 批量合并实体完成: count={}", entities.size());
        return entities;
    }

    /**
     * 按 entityId + userId 查找实体。
     */
    public Optional<GraphEntity> findEntity(String entityId, Long userId) {
        return sessionFactory.withReadSession(session -> {
            Result result = session.run(
                "MATCH (e:Entity {entityId: $entityId, userId: $userId}) RETURN e, id(e) AS neo4jId",
                parameters("entityId", entityId, "userId", userId)
            );
            if (result.hasNext()) {
                return Optional.of(mapToEntity(result.next()));
            }
            return Optional.empty();
        });
    }

    /**
     * 按名称模糊搜索实体（用户隔离）。
     */
    public List<GraphEntity> searchEntities(String keyword, Long userId, int limit) {
        return sessionFactory.withReadSession(session -> {
            Result result = session.run(
                "MATCH (e:Entity) WHERE e.userId = $userId AND e.name CONTAINS $keyword " +
                "RETURN e, id(e) AS neo4jId ORDER BY e.name LIMIT $limit",
                parameters("userId", userId, "keyword", keyword, "limit", limit)
            );
            return result.list().stream().map(this::mapToEntity).collect(Collectors.toList());
        });
    }

    // ==================== 关系操作 ====================

    /**
     * 创建关系（如果已存在则跳过）。
     */
    public GraphRelation createRelation(GraphRelation relation) {
        if (relation.getCreatedAt() == null) relation.setCreatedAt(Instant.now().toEpochMilli());

        return sessionFactory.withWriteSession(session -> {
            Result result = session.run(
                """
                MATCH (a:Entity {entityId: $fromEntityId, userId: $userId})
                MATCH (b:Entity {entityId: $toEntityId, userId: $userId})
                MERGE (a)-[r:RELATION {type: $relType, userId: $userId}]->(b)
                ON CREATE SET
                    r.sourceDocId = $sourceDocId,
                    r.properties = $properties,
                    r.createdAt = $createdAt
                RETURN r, id(r) AS neo4jId
                """,
                parameters(
                    "fromEntityId", relation.getFromEntityId(),
                    "toEntityId", relation.getToEntityId(),
                    "userId", relation.getUserId(),
                    "relType", relation.getType(),
                    "sourceDocId", relation.getSourceDocId() != null ? relation.getSourceDocId() : "",
                    "properties", relation.getProperties() != null ? relation.getProperties() : Map.of(),
                    "createdAt", relation.getCreatedAt()
                )
            );

            if (result.hasNext()) {
                Record record = result.next();
                relation.setNeo4jId(record.get("neo4jId").asLong());
            }
            return relation;
        });
    }

    /**
     * 批量创建关系（UNWIND 批量写入）。
     */
    public List<GraphRelation> createRelationsBatch(List<GraphRelation> relations) {
        if (relations == null || relations.isEmpty()) return Collections.emptyList();

        long now = Instant.now().toEpochMilli();
        relations.forEach(r -> {
            if (r.getCreatedAt() == null) r.setCreatedAt(now);
        });

        List<Map<String, Object>> batch = relations.stream()
                .map(r -> Map.<String, Object>of(
                    "fromEntityId", r.getFromEntityId(),
                    "toEntityId", r.getToEntityId(),
                    "userId", r.getUserId(),
                    "relType", r.getType(),
                    "sourceDocId", r.getSourceDocId() != null ? r.getSourceDocId() : "",
                    "properties", r.getProperties() != null ? r.getProperties() : Map.of(),
                    "createdAt", r.getCreatedAt()
                ))
                .collect(Collectors.toList());

        sessionFactory.withWriteSession(session -> {
            session.run(
                """
                UNWIND $batch AS row
                MATCH (a:Entity {entityId: row.fromEntityId, userId: row.userId})
                MATCH (b:Entity {entityId: row.toEntityId, userId: row.userId})
                MERGE (a)-[r:RELATION {type: row.relType, userId: row.userId}]->(b)
                ON CREATE SET
                    r.sourceDocId = row.sourceDocId,
                    r.properties = row.properties,
                    r.createdAt = row.createdAt
                """,
                parameters("batch", batch)
            );
        });

        log.info("✅ [图谱] 批量创建关系完成: count={}", relations.size());
        return relations;
    }

    // ==================== 子图查询 ====================

    /**
     * 以指定实体为种子，扩展 N 跳子图（用户隔离）。
     *
     * @param entityName 实体名称（模糊匹配）
     * @param userId     用户 ID
     * @param maxHops    最大跳数（1~2）
     * @return 子图数据（节点列表 + 关系列表）
     */
    public Map<String, Object> expandSubgraph(String entityName, Long userId, int maxHops) {
        int hops = Math.min(maxHops, 2);

        return sessionFactory.withReadSession(session -> {
            String cypher = String.format(
                """
                MATCH (start:Entity {name: $entityName, userId: $userId})
                MATCH path = (start)-[r:RELATION*1..%d]-(neighbor:Entity)
                WHERE neighbor.userId = $userId
                RETURN path, start, neighbor, r
                LIMIT 500
                """, hops
            );

            Result result = session.run(cypher,
                parameters("entityName", entityName, "userId", userId));

            Set<Map<String, Object>> nodes = new LinkedHashSet<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            Set<String> seenEdges = new HashSet<>();

            while (result.hasNext()) {
                Record record = result.next();
                // 收集起始节点和邻居节点
                for (String key : List.of("start", "neighbor")) {
                    Value nodeVal = record.get(key);
                    if (!nodeVal.isNull()) {
                        nodes.add(Map.of(
                            "entityId", nodeVal.get("entityId").asString(),
                            "name", nodeVal.get("name").asString(),
                            "type", nodeVal.get("type").asString()
                        ));
                    }
                }
                // 收集关系
                Value relVal = record.get("r");
                if (!relVal.isNull()) {
                    for (var rel : relVal.values()) {
                        String edgeKey = rel.get("type").asString() + ":" +
                            rel.get("userId").asLong();
                        if (seenEdges.add(edgeKey)) {
                            edges.add(Map.of(
                                "type", rel.get("type").asString()
                            ));
                        }
                    }
                }
            }

            Map<String, Object> subgraph = new LinkedHashMap<>();
            subgraph.put("nodes", new ArrayList<>(nodes));
            subgraph.put("edges", edges);
            log.info("🔍 [图谱子图] entity={}, hops={}, nodes={}, edges={}",
                entityName, hops, nodes.size(), edges.size());
            return subgraph;
        });
    }

    // ==================== 删除操作 ====================

    /**
     * 删除用户的所有图谱数据（节点 + 关系）。
     */
    public int deleteAllByUser(Long userId) {
        return sessionFactory.withWriteSession(session -> {
            // 先删除关系
            Result relResult = session.run(
                "MATCH ()-[r:RELATION {userId: $userId}]->() DELETE r RETURN count(r) AS deleted",
                parameters("userId", userId)
            );
            long relCount = relResult.hasNext() ? relResult.next().get("deleted").asLong() : 0;

            // 再删除节点
            Result nodeResult = session.run(
                "MATCH (e:Entity {userId: $userId}) DELETE e RETURN count(e) AS deleted",
                parameters("userId", userId)
            );
            long nodeCount = nodeResult.hasNext() ? nodeResult.next().get("deleted").asLong() : 0;

            log.warn("🗑️ [图谱删除] userId={}, 节点={}, 关系={}", userId, nodeCount, relCount);
            return (int) (nodeCount + relCount);
        });
    }

    /**
     * 统计用户图谱规模。
     */
    public Map<String, Long> stats(Long userId) {
        return sessionFactory.withReadSession(session -> {
            Result result = session.run(
                """
                MATCH (e:Entity {userId: $userId})
                WITH count(e) AS nodeCount
                MATCH ()-[r:RELATION {userId: $userId}]->()
                RETURN nodeCount, count(r) AS relCount
                """,
                parameters("userId", userId)
            );
            if (result.hasNext()) {
                Record record = result.next();
                return Map.of(
                    "nodeCount", record.get("nodeCount").asLong(),
                    "relCount", record.get("relCount").asLong()
                );
            }
            return Map.of("nodeCount", 0L, "relCount", 0L);
        });
    }

    // ==================== 工具方法 ====================

    private GraphEntity mapToEntity(Record record) {
        Value node = record.get("e");
        long neo4jId = record.get("neo4jId").asLong();

        Map<String, Object> props = new HashMap<>();
        Value propsVal = node.get("properties");
        if (!propsVal.isNull() && !propsVal.asMap().isEmpty()) {
            props.putAll(propsVal.asMap());
        }

        return GraphEntity.builder()
                .neo4jId(neo4jId)
                .entityId(node.get("entityId").asString())
                .name(node.get("name").asString())
                .type(node.get("type").asString())
                .userId(node.get("userId").asLong())
                .sourceDocIds(node.get("sourceDocIds").asString(""))
                .properties(props)
                .createdAt(node.get("createdAt").asLong(0L))
                .updatedAt(node.get("updatedAt").asLong(0L))
                .build();
    }
}
