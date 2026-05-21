package com.kanade.backend.graph;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kanade.backend.graph.model.GraphEntity;
import com.kanade.backend.graph.model.GraphRelation;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱 LLM 抽取模块。
 * 调用 LLM 从文档批次中抽取实体和关系，解析为结构化三元组列表。
 * 包含 Prompt 模板、JSON 解析和指数退避重试（最多 3 次）。
 *
 * @author kanade
 */
@Slf4j
@Service
public class GraphExtractionService {

    private final ChatModel extractionChatModel;
    private final Gson gson;

    /**
     * 使用通用 ChatModel（与 RAG 共用同一 Bean），
     * 不再依赖独立的 extractionChatModel。避免 DeepSeek API 兼容性问题。
     */
    public GraphExtractionService(ChatModel extractionChatModel) {
        this.extractionChatModel = extractionChatModel;
        this.gson = new Gson();
    }

    /**
     * 单次抽取结果。
     */
    public record ExtractionResult(
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        int retries,
        boolean success,
        String errorMessage
    ) {
        public static ExtractionResult success(List<GraphEntity> e, List<GraphRelation> r, int retries) {
            return new ExtractionResult(e, r, retries, true, null);
        }
        public static ExtractionResult failure(String msg, int retries) {
            return new ExtractionResult(List.of(), List.of(), retries, false, msg);
        }
    }

    /**
     * 从一批文档中抽取实体和关系。
     *
     * @param batchText 批次内所有文档文本拼接（带文档标记）
     * @param userId    用户 ID
     * @param docIdMap  文档 ID → 文档名称映射（用于来源标注）
     * @return 抽取结果
     */
    public ExtractionResult extract(String batchText, Long userId, Map<Long, String> docIdMap) {
        return extractWithRetry(batchText, userId, docIdMap, 0);
    }

    /**
     * 带重试的抽取（指数退避：1s → 2s → 4s）。
     */
    private ExtractionResult extractWithRetry(String batchText, Long userId,
                                               Map<Long, String> docIdMap, int attempt) {
        int maxRetries = 3;
        try {
            String prompt = buildExtractionPrompt(batchText);
            log.info("🤖 [LLM抽取] 第{}次尝试, promptLength={}", attempt + 1, prompt.length());

            String response = extractionChatModel.chat(prompt);
            log.info("📥 [LLM响应] length={}, content前200字符={}",
                response != null ? response.length() : 0,
                response != null ? response.substring(0, Math.min(200, response.length())) : "null");

            ExtractionResult result = parseResponse(response, userId, docIdMap);
            if (result.success()) {
                log.info("✅ [LLM抽取成功] 实体={}, 关系={}, 重试次数={}",
                    result.entities().size(), result.relations().size(), attempt);
                return result;
            }

            // 解析失败——重试
            if (attempt < maxRetries - 1) {
                long sleepMs = (long) Math.pow(2, attempt) * 1000;
                log.warn("⚠️ [LLM抽取解析失败] 第{}次, {}ms后重试", attempt + 1, sleepMs);
                Thread.sleep(sleepMs);
                return extractWithRetry(batchText, userId, docIdMap, attempt + 1);
            }

            return result;
        } catch (Exception e) {
            log.error("❌ [LLM抽取异常] 第{}次, error={}", attempt + 1, e.getMessage());

            if (attempt < maxRetries - 1) {
                try {
                    long sleepMs = (long) Math.pow(2, attempt) * 1000;
                    Thread.sleep(sleepMs);
                    return extractWithRetry(batchText, userId, docIdMap, attempt + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            return ExtractionResult.failure(e.getMessage(), attempt + 1);
        }
    }

    /**
     * 构建抽取 Prompt 模板。
     * 要求 LLM 输出严格 JSON，包含实体列表和关系列表。
     */
    private String buildExtractionPrompt(String batchText) {
        return """
            你是一个专业的知识图谱构建助手。请从以下文档内容中抽取关键实体和关系，
            构建知识图谱的三元组。

            ## 抽取规则
            1. **实体类型**：从以下类型中选择最合适的（可扩展）：
               - Person（人物）
               - Organization（组织/公司）
               - Project（项目/产品）
               - Concept（概念/术语）
               - Location（地点）
               - Event（事件）
               - Technology（技术/工具）

            2. **关系类型**：描述实体间的语义关系，例如：
               - WORKS_FOR（任职于）
               - CREATED（创建了）
               - BELONGS_TO（属于）
               - RELATED_TO（相关）
               - INCLUDES（包含）
               - USES（使用）
               - PARTICIPATED_IN（参与了）
               - LOCATED_IN（位于）

            3. **要求**：
               - 每个实体必须有唯一的 entityId（格式：e+序号，如 e1、e2）
               - 关系用 entityId 引用实体
               - 实体名称尽量规范统一
               - 忽略过于泛化的实体（如"研究"、"方法"等无具体指向的词）

            ## 输出格式
            严格按以下 JSON 格式输出（只输出 JSON，不要任何其他文字）：

            ```json
            {
              "entities": [
                {
                  "entityId": "e1",
                  "name": "实体名称",
                  "type": "实体类型",
                  "sourceDocId": "来源文档ID"
                }
              ],
              "relations": [
                {
                  "fromEntityId": "e1",
                  "toEntityId": "e2",
                  "type": "关系类型"
                }
              ]
            }
            ```

            ## 文档内容
            """ + batchText;
    }

    /**
     * 解析 LLM JSON 响应。
     */
    private ExtractionResult parseResponse(String response, Long userId,
                                            Map<Long, String> docIdMap) {
        if (response == null || response.isBlank()) {
            return ExtractionResult.failure("LLM 返回为空", 0);
        }

        try {
            // 提取 JSON 块（处理 LLM 可能包裹的 markdown 代码块）
            String json = extractJson(response);
            Type type = new TypeToken<LllmResponse>() {}.getType();
            LllmResponse llmResponse = gson.fromJson(json, type);

            if (llmResponse == null || llmResponse.entities == null) {
                log.error("❌ [JSON解析] 实体列表为空, json={}, rawResponse前200字符={}",
                    json, response.substring(0, Math.min(200, response.length())));
                return ExtractionResult.failure("JSON 解析后实体列表为空", 0);
            }

            if (llmResponse.entities.isEmpty()) {
                log.warn("⚠️ [JSON解析] 实体列表长度为0, json={}", json);
                return ExtractionResult.failure("抽取结果无有效实体", 0);
            }

            // 转换为 GraphEntity
            List<GraphEntity> entities = llmResponse.entities.stream()
                .map(e -> GraphEntity.builder()
                    .entityId(e.entityId)
                    .name(e.name)
                    .type(e.type != null ? e.type : "Concept")
                    .userId(userId)
                    .sourceDocIds(e.sourceDocId != null ? e.sourceDocId : "")
                    .build())
                .collect(Collectors.toList());

            // 转换为 GraphRelation
            List<GraphRelation> relations = new ArrayList<>();
            if (llmResponse.relations != null) {
                relations = llmResponse.relations.stream()
                    .map(r -> GraphRelation.builder()
                        .fromEntityId(r.fromEntityId)
                        .toEntityId(r.toEntityId)
                        .type(r.type != null ? r.type : "RELATED_TO")
                        .userId(userId)
                        .build())
                    .collect(Collectors.toList());
            }

            if (entities.isEmpty()) {
                return ExtractionResult.failure("抽取结果无有效实体", 0);
            }

            return ExtractionResult.success(entities, relations, 0);
        } catch (Exception e) {
            log.error("❌ [JSON解析失败] error={}, rawResponse={}", e.getMessage(),
                response.substring(0, Math.min(200, response.length())));
            return ExtractionResult.failure("JSON 解析异常: " + e.getMessage(), 0);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（处理 markdown 代码块包装）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();

        // 尝试提取 ```json ... ``` 代码块
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart);
            int jsonEnd = trimmed.indexOf("```", contentStart > 0 ? contentStart : jsonStart + 7);
            if (contentStart > 0 && jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }

        // 尝试提取 ``` ... ``` （无语言标记）
        int codeStart = trimmed.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = trimmed.indexOf('\n', codeStart);
            int codeEnd = trimmed.indexOf("```", contentStart > 0 ? contentStart : codeStart + 3);
            if (contentStart > 0 && codeEnd > contentStart) {
                return trimmed.substring(contentStart, codeEnd).trim();
            }
        }

        // 尝试提取 { ... } 直接 JSON
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1).trim();
        }

        return trimmed;
    }

    // ==================== LLM JSON 结构映射 ====================

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
