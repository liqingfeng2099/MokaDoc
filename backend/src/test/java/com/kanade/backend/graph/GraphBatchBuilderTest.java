package com.kanade.backend.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphBatchBuilder 单元测试。
 * 测试贪心分批算法的正确性和边界情况。
 *
 * @author kanade
 */
@DisplayName("智能分批模块")
class GraphBatchBuilderTest {

    private GraphBatchBuilder batchBuilder;

    @BeforeEach
    void setUp() {
        batchBuilder = new GraphBatchBuilder();
        // 设置较小的上限便于测试
        setField(batchBuilder, "maxTokensPerBatch", 1000);
    }

    @Test
    @DisplayName("正常分批：多个文档按 token 上限分配")
    void shouldBuildBatchesWithinLimit() {
        List<GraphDocumentPreprocessor.PreprocessedDoc> docs = List.of(
            doc(1, "A", 400),
            doc(2, "B", 350),
            doc(3, "C", 300),
            doc(4, "D", 200),
            doc(5, "E", 150)
        );

        GraphBatchBuilder.BatchResult result = batchBuilder.buildBatches(docs);

        assertTrue(result.batches().size() >= 2, "至少 2 个批次");
        // 每个批次 token 不超过上限
        for (GraphBatchBuilder.Batch batch : result.batches()) {
            assertTrue(batch.totalTokens() <= 1000,
                "批次 " + batch.batchIndex() + " token 超限: " + batch.totalTokens());
        }
        assertEquals(5, result.totalDocs());
    }

    @Test
    @DisplayName("单文档超限：应单独成批并警告")
    void shouldHandleOverflowDocument() {
        List<GraphDocumentPreprocessor.PreprocessedDoc> docs = List.of(
            doc(1, "超大文档", 1500),
            doc(2, "小文档", 200)
        );

        GraphBatchBuilder.BatchResult result = batchBuilder.buildBatches(docs);

        // 超大文档单独成批
        assertEquals(2, result.batches().size());
        assertEquals(1500, result.batches().get(0).totalTokens());
        assertEquals(1, result.batches().get(0).docs().size());
    }

    @Test
    @DisplayName("空列表：应返回空结果")
    void shouldHandleEmptyList() {
        GraphBatchBuilder.BatchResult result = batchBuilder.buildBatches(List.of());

        assertEquals(0, result.totalDocs());
        assertEquals(0, result.totalTokens());
        assertTrue(result.batches().isEmpty());
    }

    @Test
    @DisplayName("单文档刚好等于上限")
    void shouldHandleExactLimit() {
        List<GraphDocumentPreprocessor.PreprocessedDoc> docs = List.of(
            doc(1, "刚好1000", 1000)
        );

        GraphBatchBuilder.BatchResult result = batchBuilder.buildBatches(docs);

        assertEquals(1, result.batches().size());
        assertEquals(1000, result.batches().get(0).totalTokens());
    }

    @Test
    @DisplayName("贪心算法应尽量减少批次数")
    void shouldMinimizeBatchCount() {
        List<GraphDocumentPreprocessor.PreprocessedDoc> docs = new ArrayList<>();
        // 生成 10 个 300 token 的文档，1000 上限理论上每批最多 3 个
        for (int i = 1; i <= 10; i++) {
            docs.add(doc(i, "Doc" + i, 300));
        }

        GraphBatchBuilder.BatchResult result = batchBuilder.buildBatches(docs);

        // 贪心算法：每批 3 个（900 ≤ 1000），10/3 = 4 批（最后一批 1 个）
        assertTrue(result.batches().size() <= 4,
            "应 ≤ 4 批，实际: " + result.batches().size());
        assertEquals(10, result.totalDocs());
    }

    // ==================== 工具方法 ====================

    private GraphDocumentPreprocessor.PreprocessedDoc doc(long id, String name, int tokens) {
        return new GraphDocumentPreprocessor.PreprocessedDoc(id, name, "test text", tokens);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
