package com.kanade.backend.ai.rag.transformer;

import java.util.List;

/**
 * LLM 多策略查询 JSON 响应的反序列化 POJO。
 * 用于 Gson 解析 MultiStrategyQueryTransformer 中 LLM 返回的结构化 JSON。
 */
public class StrategyQueryResult {

    private List<QueryItem> queries;

    public List<QueryItem> getQueries() {
        return queries;
    }

    public void setQueries(List<QueryItem> queries) {
        this.queries = queries;
    }

    public static class QueryItem {
        private String text;
        private String type;   // hyde | step_back
        private String target; // vector | text

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }
}
