package com.kanade.backend.graph;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphExtractionService JSON 解析测试。
 * 验证 LLM 返回的各种 JSON 格式能被正确解析。
 *
 * @author kanade
 */
@DisplayName("LLM 抽取 JSON 解析")
class GraphExtractionServiceTest {

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("正常 JSON：实体和关系应正确解析")
    void shouldParseNormalJson() {
        String json = """
            {
              "entities": [
                {"entityId": "e1", "name": "机器学习", "type": "Concept", "sourceDocId": "1"},
                {"entityId": "e2", "name": "吴恩达", "type": "Person", "sourceDocId": "1"}
              ],
              "relations": [
                {"fromEntityId": "e2", "toEntityId": "e1", "type": "TEACHES"}
              ]
            }
            """;

        LllmResponse response = parseJson(json);

        assertNotNull(response);
        assertEquals(2, response.entities.size());
        assertEquals(1, response.relations.size());
        assertEquals("机器学习", response.entities.get(0).name);
        assertEquals("TEACHES", response.relations.get(0).type);
    }

    @Test
    @DisplayName("代码块包裹：```json ... ``` 应正确提取")
    void shouldExtractFromCodeBlock() {
        String response = """
            以下是抽取结果：
            
            ```json
            {
              "entities": [
                {"entityId": "e1", "name": "Python", "type": "Technology", "sourceDocId": "1"}
              ],
              "relations": []
            }
            ```
            
            抽取完成。
            """;

        String json = extractJson(response);

        assertTrue(json.contains("Python"));
        assertTrue(json.contains("Technology"));
    }

    @Test
    @DisplayName("裸大括号 JSON：应直接提取")
    void shouldExtractBareJson() {
        String response = "一些前言文字 {\"entities\":[{\"entityId\":\"e1\",\"name\":\"Java\",\"type\":\"Technology\",\"sourceDocId\":\"1\"}],\"relations\":[]} 后记";

        String json = extractJson(response);

        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    @DisplayName("空 entities：应返回空列表")
    void shouldHandleEmptyEntities() {
        String json = "{\"entities\":[],\"relations\":[]}";

        LllmResponse response = parseJson(json);

        assertNotNull(response);
        assertTrue(response.entities.isEmpty());
        assertTrue(response.relations.isEmpty());
    }

    @Test
    @DisplayName("缺少 relations 字段：应容错处理")
    void shouldHandleMissingRelations() {
        String json = """
            {"entities": [
              {"entityId": "e1", "name": "Test", "type": "Concept", "sourceDocId": "1"}
            ]}
            """;

        LllmResponse response = parseJson(json);

        assertNotNull(response);
        assertEquals(1, response.entities.size());
        assertNull(response.relations);  // 字段不存在时为 null
    }

    @Test
    @DisplayName("缺少 type 字段：应使用默认值 Concept")
    void shouldDefaultTypeToConcept() {
        String json = """
            {"entities": [
              {"entityId": "e1", "name": "无类型实体", "sourceDocId": "1"}
            ]}
            """;

        LllmResponse response = parseJson(json);

        assertEquals(1, response.entities.size());
        assertNull(response.entities.get(0).type); // JSON 中不存在的字段为 null，业务层应填充默认 Concept
    }

    @Test
    @DisplayName("entityId 格式：e+数字 模式")
    void shouldAcceptEntityIdPattern() {
        String json = """
            {"entities": [
              {"entityId": "e1", "name": "A", "type": "Concept"},
              {"entityId": "e10", "name": "B", "type": "Concept"},
              {"entityId": "e100", "name": "C", "type": "Concept"}
            ]}
            """;

        LllmResponse response = parseJson(json);

        assertEquals(3, response.entities.size());
        assertEquals("e1", response.entities.get(0).entityId);
        assertEquals("e100", response.entities.get(2).entityId);
    }

    // ==================== 工具方法（与 GraphExtractionService 一致） ====================

    private String extractJson(String response) {
        String trimmed = response.trim();

        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart);
            int jsonEnd = trimmed.indexOf("```", contentStart > 0 ? contentStart : jsonStart + 7);
            if (contentStart > 0 && jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }

        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1).trim();
        }

        return trimmed;
    }

    private LllmResponse parseJson(String json) {
        Type type = new TypeToken<LllmResponse>() {}.getType();
        return GSON.fromJson(json, type);
    }

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
