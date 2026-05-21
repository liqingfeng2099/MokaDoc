package com.kanade.backend.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 图谱关系边模型。
 * 对应 Neo4j 中两个 Entity 节点之间的有向关系。
 *
 * @author kanade
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关系唯一标识（Neo4j 内部 id）
     */
    private Long neo4jId;

    /**
     * 起始实体业务 ID
     */
    private String fromEntityId;

    /**
     * 目标实体业务 ID
     */
    private String toEntityId;

    /**
     * 关系类型（如 WORKS_FOR、BELONGS_TO、RELATED_TO 等，可扩展）
     */
    private String type;

    /**
     * 所属用户 ID（多租户隔离）
     */
    private Long userId;

    /**
     * 来源文档 ID
     */
    private String sourceDocId;

    /**
     * 扩展属性
     */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    /**
     * 创建时间戳
     */
    private Long createdAt;
}
