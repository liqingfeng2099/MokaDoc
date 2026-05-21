package com.kanade.backend.graph;

import com.kanade.backend.graph.model.GraphEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱实体对齐模块。
 * 跨批次合并同名同类型实体，检测同名不同类型冲突。
 * 使用内存哈希 + 编辑距离规则进行相似度匹配。
 *
 * @author kanade
 */
@Slf4j
@Component
public class GraphEntityAligner {

    /**
     * 对齐结果。
     */
    public record AlignmentResult(
        List<GraphEntity> mergedEntities,      // 合并后的实体列表
        List<ConflictRecord> conflicts,        // 冲突记录
        int originalCount,                      // 对齐前实体数
        int mergedCount,                        // 对齐后实体数（去重后）
        int duplicateCount                      // 合并的重复实体数
    ) {}

    /**
     * 冲突记录——同名但不同类型的实体。
     */
    public record ConflictRecord(
        String name,
        List<String> types,
        List<String> entityIds
    ) {}

    /**
     * 执行实体对齐（内存哈希合并 + 编辑距离相似度去重）。
     *
     * @param entities 待对齐的实体列表（可能来自多个批次）
     * @return 对齐结果
     */
    public AlignmentResult align(List<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return new AlignmentResult(List.of(), List.of(), 0, 0, 0);
        }

        int originalCount = entities.size();
        log.info("🔄 [实体对齐] 开始, 原始实体数={}", originalCount);

        // 阶段 1：同名同类型合并（精确匹配）
        Map<String, GraphEntity> exactMap = new LinkedHashMap<>();
        Map<String, List<GraphEntity>> conflictCandidates = new LinkedHashMap<>(); // 同名但类型可能不同
        int exactDupes = 0;

        for (GraphEntity entity : entities) {
            String nameKey = normalizeName(entity.getName());
            String typeKey = nameKey + "::" + entity.getType();

            if (exactMap.containsKey(typeKey)) {
                // 同名同类型——合并源文档 ID
                GraphEntity existing = exactMap.get(typeKey);
                existing.setSourceDocIds(mergeDocIds(existing.getSourceDocIds(), entity.getSourceDocIds()));
                exactDupes++;
            } else {
                exactMap.put(typeKey, entity);
            }

            // 记录同名记录（用于冲突检测和相似度匹配）
            conflictCandidates.computeIfAbsent(nameKey, k -> new ArrayList<>()).add(entity);
        }

        // 阶段 2：冲突检测——同名不同类型
        List<ConflictRecord> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<GraphEntity>> entry : conflictCandidates.entrySet()) {
            Set<String> types = entry.getValue().stream()
                .map(GraphEntity::getType)
                .collect(Collectors.toSet());
            if (types.size() > 1) {
                conflicts.add(new ConflictRecord(
                    entry.getKey(),
                    new ArrayList<>(types),
                    entry.getValue().stream().map(GraphEntity::getEntityId).collect(Collectors.toList())
                ));
            }
        }

        // 阶段 3：编辑距离相似度去重（同名不同 entityId 但编辑距离 < 阈值的实体）
        int fuzzyMerges = 0;
        List<GraphEntity> mergedEntities = new ArrayList<>(exactMap.values());
        Map<String, GraphEntity> fuzzyMap = new LinkedHashMap<>();

        for (GraphEntity entity : mergedEntities) {
            String nameKey = normalizeName(entity.getName());
            boolean matched = false;

            for (Map.Entry<String, GraphEntity> existing : fuzzyMap.entrySet()) {
                String existingName = existing.getKey();
                if (entity.getType().equals(existing.getValue().getType())
                    && editDistance(nameKey, existingName) <= 2) {
                    // 编辑距离 ≤ 2，且同类型——合并
                    GraphEntity exist = existing.getValue();
                    exist.setSourceDocIds(mergeDocIds(exist.getSourceDocIds(), entity.getSourceDocIds()));
                    matched = true;
                    fuzzyMerges++;
                    break;
                }
            }

            if (!matched) {
                fuzzyMap.put(nameKey, entity);
            }
        }

        List<GraphEntity> finalEntities = new ArrayList<>(fuzzyMap.values());
        int totalDuplicates = exactDupes + fuzzyMerges;

        log.info("✅ [实体对齐完成] 原始={}, 对齐后={}, 精确合并={}, 模糊合并={}, 冲突={}",
            originalCount, finalEntities.size(), exactDupes, fuzzyMerges, conflicts.size());

        for (ConflictRecord c : conflicts) {
            log.warn("⚠️ [冲突] name={}, types={}", c.name(), c.types());
        }

        return new AlignmentResult(finalEntities, conflicts, originalCount,
            finalEntities.size(), totalDuplicates);
    }

    // ==================== 工具方法 ====================

    /**
     * 标准化实体名称（小写 + 去空格）。
     */
    private String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /**
     * 合并源文档 ID（去重，逗号拼接）。
     */
    private String mergeDocIds(String existing, String incoming) {
        Set<String> ids = new LinkedHashSet<>();
        if (existing != null && !existing.isEmpty()) {
            ids.addAll(Arrays.asList(existing.split(",")));
        }
        if (incoming != null && !incoming.isEmpty()) {
            ids.addAll(Arrays.asList(incoming.split(",")));
        }
        return String.join(",", ids);
    }

    /**
     * Levenshtein 编辑距离。
     */
    private int editDistance(String a, String b) {
        if (a == null || b == null) return Math.max(a == null ? 0 : a.length(), b == null ? 0 : b.length());
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)
                );
            }
        }
        return dp[a.length()][b.length()];
    }
}
