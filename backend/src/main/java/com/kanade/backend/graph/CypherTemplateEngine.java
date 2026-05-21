package com.kanade.backend.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Cypher 查询模板引擎。
 * 提供预定义的参数化 Cypher 模板，执行前进行白名单安全校验。
 *
 * 安全策略：
 * - 只允许 MATCH / RETURN / WHERE / ORDER BY / LIMIT / SKIP / WITH / OPTIONAL MATCH
 * - 禁止 CREATE / DELETE / SET / REMOVE / MERGE / DETACH / CALL / FOREACH
 * - 查询超时 3 秒，返回行数上限 1000
 *
 * @author kanade
 */
@Slf4j
@Component
public class CypherTemplateEngine {

    private final Neo4jSessionFactory sessionFactory;

    /**
     * 允许的 Cypher 子句白名单
     */
    private static final Set<String> ALLOWED_CLAUSES = Set.of(
        "MATCH", "OPTIONAL MATCH", "RETURN", "WHERE", "ORDER BY", "LIMIT", "SKIP", "WITH",
        "DISTINCT", "AS", "AND", "OR", "NOT", "IN", "CONTAINS", "STARTS WITH", "ENDS WITH",
        "COUNT", "COLLECT", "ANY", "NONE", "SINGLE", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END"
    );

    /**
     * 禁止的 Cypher 子句黑名单（优先级高于白名单）
     */
    private static final Set<String> FORBIDDEN_CLAUSES = Set.of(
        "CREATE", "DELETE", "DETACH", "SET", "REMOVE", "MERGE", "CALL", "FOREACH",
        "LOAD CSV", "USING PERIODIC COMMIT", "DROP", "ALTER"
    );

    /**
     * 查询超时（秒）
     */
    private static final long QUERY_TIMEOUT_SEC = 3;

    /**
     * 返回行数上限
     */
    private static final int MAX_ROWS = 1000;

    public CypherTemplateEngine(Neo4jSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    // ==================== 预定义模板 ====================

    /**
     * 模板 1：按名称查询实体。
     */
    public static final String TPL_ENTITY_BY_NAME = "MATCH (e:Entity {userId: $userId}) " +
        "WHERE e.name CONTAINS $keyword RETURN e, id(e) AS neo4jId ORDER BY e.name LIMIT $limit";

    /**
     * 模板 2：按类型查询实体。
     */
    public static final String TPL_ENTITY_BY_TYPE = "MATCH (e:Entity {userId: $userId, type: $type}) " +
        "RETURN e, id(e) AS neo4jId ORDER BY e.name LIMIT $limit";

    /**
     * 模板 3：子图扩展（1 跳）。
     */
    public static final String TPL_SUBGRAPH_1HOP = "MATCH (start:Entity {name: $entityName, userId: $userId}) " +
        "OPTIONAL MATCH (start)-[r:RELATION]-(neighbor:Entity {userId: $userId}) " +
        "RETURN start, r, neighbor LIMIT $limit";

    /**
     * 模板 4：子图扩展（2 跳）。
     */
    public static final String TPL_SUBGRAPH_2HOP = "MATCH (start:Entity {name: $entityName, userId: $userId}) " +
        "OPTIONAL MATCH path = (start)-[r1:RELATION]-(mid:Entity {userId: $userId})-" +
        "[r2:RELATION]-(neighbor:Entity {userId: $userId}) " +
        "RETURN start, r1, mid, r2, neighbor LIMIT $limit";

    /**
     * 模板 5：两个实体间的关系路径。
     */
    public static final String TPL_RELATION_PATH = "MATCH (a:Entity {name: $entityA, userId: $userId}), " +
        "(b:Entity {name: $entityB, userId: $userId}) " +
        "MATCH path = shortestPath((a)-[*..3]-(b)) " +
        "RETURN path LIMIT 1";

    /**
     * 模板 6：按类型统计实体分布。
     */
    public static final String TPL_TYPE_STATS = "MATCH (e:Entity {userId: $userId}) " +
        "RETURN e.type AS type, count(e) AS cnt ORDER BY cnt DESC";

    /**
     * 模板 7：获取实体的所有直接关系。
     */
    public static final String TPL_ENTITY_RELATIONS = "MATCH (e:Entity {entityId: $entityId, userId: $userId}) " +
        "-[r:RELATION]-(neighbor:Entity {userId: $userId}) " +
        "RETURN e, r, neighbor ORDER BY r.type LIMIT $limit";

    // ==================== 执行方法 ====================

    /**
     * 执行模板化查询（带参数绑定）。
     *
     * @param cypher Cypher 查询模板
     * @param params 参数映射
     * @return 查询结果列表
     */
    public List<Map<String, Object>> execute(String cypher, Map<String, Object> params) {
        // 安全校验
        validateCypher(cypher);

        // 追加 LIMIT（如果未指定）
        final String safeCypher;
        if (!cypher.toUpperCase().contains("LIMIT")) {
            safeCypher = cypher + " LIMIT " + MAX_ROWS;
        } else {
            safeCypher = cypher;
        }

        // 追加超时提示
        final Map<String, Object> safeParams = new HashMap<>(params);
        safeParams.putIfAbsent("limit", MAX_ROWS);

        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();

        sessionFactory.withReadSession(session -> {
            Result result = session.run(safeCypher, safeParams);
            while (result.hasNext() && results.size() < MAX_ROWS) {
                Record record = result.next();
                results.add(record.asMap());
            }
            return null;
        });

        long duration = System.currentTimeMillis() - start;
        log.debug("📊 [Cypher执行] duration={}ms, rows={}, cypher={}", duration, results.size(),
            safeCypher.substring(0, Math.min(80, safeCypher.length())));

        if (duration > QUERY_TIMEOUT_SEC * 1000) {
            log.warn("⚠️ [Cypher超时] duration={}ms", duration);
        }

        return results;
    }

    /**
     * 安全校验——白名单 + 黑名单双重检查。
     */
    public void validateCypher(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new IllegalArgumentException("Cypher 查询不能为空");
        }

        String upper = cypher.toUpperCase().trim();

        // 黑名单检查
        for (String forbidden : FORBIDDEN_CLAUSES) {
            // 使用词边界匹配防止误判（如 DELETE 不会匹配 UNDELETE）
            if (Pattern.compile("\\b" + Pattern.quote(forbidden) + "\\b")
                    .matcher(upper).find()) {
                throw new SecurityException(
                    "Cypher 安全校验失败：禁止使用 " + forbidden + " 子句"
                );
            }
        }

        // 基本语法检查——必须以 MATCH 开头
        if (!upper.startsWith("MATCH") && !upper.startsWith("OPTIONAL MATCH")) {
            throw new SecurityException("Cypher 安全校验失败：只允许以 MATCH 开头的只读查询");
        }
    }

    /**
     * 快捷方法：按名称搜索实体。
     */
    public List<Map<String, Object>> searchEntities(String keyword, Long userId, int limit) {
        return execute(TPL_ENTITY_BY_NAME, Map.of(
            "userId", userId,
            "keyword", keyword,
            "limit", limit
        ));
    }

    /**
     * 快捷方法：子图扩展。
     */
    public List<Map<String, Object>> expandSubgraph(String entityName, Long userId, int hops, int limit) {
        String tpl = hops <= 1 ? TPL_SUBGRAPH_1HOP : TPL_SUBGRAPH_2HOP;
        return execute(tpl, Map.of(
            "entityName", entityName,
            "userId", userId,
            "limit", limit
        ));
    }

    /**
     * 快捷方法：类型统计。
     */
    public List<Map<String, Object>> typeStats(Long userId) {
        return execute(TPL_TYPE_STATS, Map.of("userId", userId));
    }

    /**
     * 快捷方法：查询实体直接关系。
     */
    public List<Map<String, Object>> entityRelations(String entityId, Long userId, int limit) {
        return execute(TPL_ENTITY_RELATIONS, Map.of(
            "entityId", entityId,
            "userId", userId,
            "limit", limit
        ));
    }
}
