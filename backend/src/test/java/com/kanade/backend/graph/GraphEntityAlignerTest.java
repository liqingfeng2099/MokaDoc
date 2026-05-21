package com.kanade.backend.graph;

import com.kanade.backend.graph.model.GraphEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphEntityAligner 单元测试。
 * 测试实体对齐的三阶段：精确合并、冲突检测、模糊合并。
 *
 * @author kanade
 */
@DisplayName("实体对齐模块")
class GraphEntityAlignerTest {

    private final GraphEntityAligner aligner = new GraphEntityAligner();

    @Test
    @DisplayName("精确合并：同名同类型实体应合并 sourceDocIds")
    void shouldMergeExactSameNameAndType() {
        List<GraphEntity> entities = List.of(
            entity("e1", "机器学习", "Concept", "1"),
            entity("e2", "机器学习", "Concept", "2"),
            entity("e3", "深度学习", "Concept", "1")
        );

        GraphEntityAligner.AlignmentResult result = aligner.align(entities);

        assertEquals(3, result.originalCount());
        assertEquals(2, result.mergedCount());   // 机器学习合并为一个，深度学习独立
        assertEquals(1, result.duplicateCount()); // 精确合并了 1 个重复
    }

    @Test
    @DisplayName("冲突检测：同名不同类型应产生冲突记录")
    void shouldDetectConflictsForSameNameDifferentType() {
        List<GraphEntity> entities = List.of(
            entity("e1", "Python", "Concept", "1"),
            entity("e2", "Python", "Technology", "1")
        );

        GraphEntityAligner.AlignmentResult result = aligner.align(entities);

        assertTrue(result.conflicts().size() > 0, "应有冲突记录");
        assertEquals("python", result.conflicts().get(0).name().toLowerCase());
        assertTrue(result.conflicts().get(0).types().contains("Concept"));
        assertTrue(result.conflicts().get(0).types().contains("Technology"));
    }

    @Test
    @DisplayName("模糊合并：编辑距离 ≤ 2 的同类型实体应合并")
    void shouldFuzzyMergeCloseNames() {
        List<GraphEntity> entities = List.of(
            entity("e1", "机器学习", "Concept", "1"),
            entity("e2", "机器学期", "Concept", "2"),  // "习" → "期" 编辑距离 1
            entity("e3", "深度学习", "Concept", "1")
        );

        GraphEntityAligner.AlignmentResult result = aligner.align(entities);

        // 精确匹配阶段：e1(e2) 不会被合并（名称不同）
        // 模糊匹配阶段：e2 与 e1 编辑距离 1，应合并
        assertEquals(3, result.originalCount());
        assertEquals(2, result.mergedCount());   // 2 个唯一条目
    }

    @Test
    @DisplayName("空输入：应返回空结果")
    void shouldHandleEmptyInput() {
        GraphEntityAligner.AlignmentResult result = aligner.align(List.of());

        assertEquals(0, result.originalCount());
        assertEquals(0, result.mergedCount());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    @DisplayName("单实体：应保持不变")
    void shouldKeepSingleEntity() {
        List<GraphEntity> entities = List.of(
            entity("e1", "唯一实体", "Concept", "1")
        );

        GraphEntityAligner.AlignmentResult result = aligner.align(entities);

        assertEquals(1, result.originalCount());
        assertEquals(1, result.mergedCount());
        assertEquals(0, result.duplicateCount());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    @DisplayName("多批次合并：跨批次 sourceDocIds 应去重拼接")
    void shouldMergeDocIdsAcrossBatches() {
        List<GraphEntity> entities = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            entities.add(entity("e" + i, "知识图谱", "Concept", String.valueOf(i)));
        }

        GraphEntityAligner.AlignmentResult result = aligner.align(entities);

        assertEquals(5, result.originalCount());
        assertEquals(1, result.mergedCount());   // 全部合并为一个
        GraphEntity merged = result.mergedEntities().get(0);
        String[] docIds = merged.getSourceDocIds().split(",");
        assertEquals(5, docIds.length);          // 5 个来源文档 ID
    }

    // ==================== 工具方法 ====================

    private GraphEntity entity(String entityId, String name, String type, String sourceDocId) {
        return GraphEntity.builder()
            .entityId(entityId)
            .name(name)
            .type(type)
            .userId(1L)
            .sourceDocIds(sourceDocId)
            .build();
    }
}
