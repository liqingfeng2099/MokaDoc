package com.kanade.backend.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱智能分批模块。
 * 使用贪心算法将预处理后的文档按 token 上限组合为批次，
 * 同一批次内的文档将在一个 LLM 调用中被抽取。
 *
 * @author kanade
 */
@Slf4j
@Component
public class GraphBatchBuilder {

    /**
     * 单批次 token 上限（默认 800K，充分利用 DeepSeek 1M 上下文）
     */
    @Value("${graph.batch.max-tokens:800000}")
    private int maxTokensPerBatch;

    /**
     * 批次——包含一组文档的预处理结果。
     */
    public record Batch(int batchIndex, List<GraphDocumentPreprocessor.PreprocessedDoc> docs, int totalTokens) {
    }

    /**
     * 分批结果——包含批次列表和统计信息。
     */
    public record BatchResult(List<Batch> batches, int totalDocs, int totalTokens) {
    }

    /**
     * 贪心分批算法：
     * 1. 将所有文档按 token 数降序排列
     * 2. 遍历文档，将其放入当前 token 余量最大的批次
     * 3. 如果无法放入任何现有批次，创建新批次
     *
     * @param docs 预处理后的文档列表
     * @return 分批结果
     */
    public BatchResult buildBatches(List<GraphDocumentPreprocessor.PreprocessedDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            log.warn("⚠️ [分批] 无文档可分批");
            return new BatchResult(List.of(), 0, 0);
        }

        // 1. 按 token 降序排列（大文档优先处理）
        List<GraphDocumentPreprocessor.PreprocessedDoc> sorted = new ArrayList<>(docs);
        sorted.sort((a, b) -> Integer.compare(b.tokenCount(), a.tokenCount()));

        // 2. 贪心分批
        List<Batch> batches = new ArrayList<>();
        List<Integer> batchTokens = new ArrayList<>(); // 每个批次的当前 token 使用量

        for (GraphDocumentPreprocessor.PreprocessedDoc doc : sorted) {
            int docTokens = doc.tokenCount();

            // 单个文档超过上限——单独成批并警告
            if (docTokens > maxTokensPerBatch) {
                log.warn("⚠️ [分批] 单文档 token 超限: docId={}, tokens={}, limit={}",
                    doc.docId(), docTokens, maxTokensPerBatch);
                Batch overflow = new Batch(batches.size(),
                    List.of(doc), docTokens);
                batches.add(overflow);
                batchTokens.add(docTokens);
                continue;
            }

            // 寻找可容纳的批次（贪心——找 token 余量最大的）
            int bestIdx = -1;
            int maxRemaining = -1;
            for (int i = 0; i < batches.size(); i++) {
                int remaining = maxTokensPerBatch - batchTokens.get(i);
                if (remaining >= docTokens && remaining > maxRemaining) {
                    bestIdx = i;
                    maxRemaining = remaining;
                }
            }

            if (bestIdx >= 0) {
                // 放入现有批次
                Batch existing = batches.get(bestIdx);
                List<GraphDocumentPreprocessor.PreprocessedDoc> newDocs = new ArrayList<>(existing.docs());
                newDocs.add(doc);
                int newTokens = batchTokens.get(bestIdx) + docTokens;
                batches.set(bestIdx, new Batch(bestIdx, newDocs, newTokens));
                batchTokens.set(bestIdx, newTokens);
            } else {
                // 创建新批次
                int newIdx = batches.size();
                batches.add(new Batch(newIdx, List.of(doc), docTokens));
                batchTokens.add(docTokens);
            }
        }

        int totalDocs = sorted.size();
        int totalTokens = batchTokens.stream().mapToInt(Integer::intValue).sum();

        log.info("✅ [分批完成] 文档数={}, 总token={}, 批次数={}", totalDocs, totalTokens, batches.size());
        for (int i = 0; i < batches.size(); i++) {
            Batch b = batches.get(i);
            log.info("  批次[{}]: 文档数={}, token={}/{} (填充率={:.0f}%)",
                i, b.docs().size(), b.totalTokens(), maxTokensPerBatch,
                100.0 * b.totalTokens() / maxTokensPerBatch);
        }

        return new BatchResult(batches, totalDocs, totalTokens);
    }
}
