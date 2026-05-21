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
 * 图谱实体节点模型。
 * 对应 Neo4j 中的一个 Entity 节点（标签为 Entity），
 * 所有节点携带 userId 实现多租户隔离。
 *
 * @author kanade
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 实体唯一标识（Neo4j 内部 id，非业务主键）
     */
    private Long neo4jId;

    /**
     * 实体业务 ID（跨批次对齐用）
     */
    private String entityId;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 实体类型（如 Person、Project、Organization、Concept 等，可扩展）
     */
    private String type;

    /**
     * 所属用户 ID（多租户隔离）
     */
    private Long userId;

    /**
     * 来源文档 ID（可多个，逗号分隔）
     */
    private String sourceDocIds;

    /**
     * 扩展属性（灵活存放按类型变化的字段）
     */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    /**
     * 创建时间戳
     */
    private Long createdAt;

    /**
     * 更新时间戳
     */
    private Long updatedAt;
}
