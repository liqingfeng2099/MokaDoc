package com.kanade.backend.ai.rag.transformer;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class EntityBasedExpander implements QueryTransformer {

    private final int maxExpansionCount;

    private static final Map<String, List<String>> SYNONYM_MAP = new HashMap<>();

    static {
        SYNONYM_MAP.put("特点", Arrays.asList("特性", "优势", "特征"));
        SYNONYM_MAP.put("优点", Arrays.asList("优势", "好处", "长处"));
        SYNONYM_MAP.put("缺点", Arrays.asList("不足", "劣势", "缺陷"));
        SYNONYM_MAP.put("方法", Arrays.asList("方式", "技巧", "做法"));
        SYNONYM_MAP.put("教程", Arrays.asList("指南", "手册", "入门"));
    }

    public EntityBasedExpander(int maxExpansionCount) {
        this.maxExpansionCount = maxExpansionCount;
    }

    @Override
    public List<Query> transform(Query query) {
        String originalQuery = query.text();

        try {
            List<Term> terms = HanLP.segment(originalQuery);

            List<String> keywords = terms.stream()
                .filter(term -> term.nature.startsWith("n") || term.nature.startsWith("v"))
                .map(term -> term.word)
                .filter(word -> word.length() > 1)
                .distinct()
                .collect(Collectors.toList());

            if (keywords.isEmpty()) {
                log.debug("🔧 [查询扩展] 未提取到关键词，返回原查询");
                return List.of(query);
            }

            Set<String> expandedQueries = new LinkedHashSet<>();
            expandedQueries.add(originalQuery);

            for (String keyword : keywords) {
                List<String> synonyms = SYNONYM_MAP.get(keyword);
                if (synonyms != null) {
                    for (String synonym : synonyms) {
                        String expanded = originalQuery.replace(keyword, synonym);
                        if (!expanded.equals(originalQuery)) {
                            expandedQueries.add(expanded);
                        }
                    }
                }
            }

            for (String keyword : keywords) {
                expandedQueries.add(originalQuery + " " + keyword);
            }

            List<Query> result = expandedQueries.stream()
                .limit(maxExpansionCount)
                .map(Query::from)
                .collect(Collectors.toList());

            // 详细记录扩展结果
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("🔧 [查询扩展] 转换详情:\n");
            logBuilder.append("  原始查询: ").append(originalQuery).append("\n");
            logBuilder.append("  扩展为 ").append(result.size()).append(" 个查询:\n");
            for (int i = 0; i < result.size(); i++) {
                logBuilder.append("    [").append(i + 1).append("] ").append(result.get(i).text()).append("\n");
            }
            log.info(logBuilder.toString());
            
            return result;
        } catch (Exception e) {
            log.error("❌ [查询扩展失败] 降级返回原查询", e);
            return List.of(query);
        }
    }
}
