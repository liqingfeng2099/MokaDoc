package com.kanade.backend;

import com.kanade.backend.ai.rag.orchestrator.AdvancedRagOrchestrator;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

/**
 * ES 文本检索功能测试
 * 用于验证 ElasticsearchContentRetriever 的 BM25 关键词匹配能力
 */
@Slf4j
@SpringBootTest
public class TextRetrievalTest {

    @Resource
    private AdvancedRagOrchestrator orchestrator;

    /**
     * 测试纯文本关键词检索
     * 场景：搜索包含特定专有名词或版本号的文档片段
     */
    @Test
    public void testKeywordSearch() {
        // 1. 从编排器中获取全文检索器 (假设在 orchestrator 中有注入或通过其他方式获取)
        // 注意：如果 orchestrator 没有暴露 getter，你可以直接在测试类中 @Resource 注入 textRetriever
        ContentRetriever textRetriever = getTextRetriever();

        if (textRetriever == null) {
            log.error("❌ 未找到 textRetriever Bean，请检查 RagRetrieverConfig 配置");
            return;
        }

        // 2. 构造查询对象
        String queryText = "Java 17"; // 尝试搜索一个具体的版本号
        Query query = Query.from(queryText);

        log.info("🔍 [开始测试] 正在对 ES 执行文本检索，关键词: '{}'", queryText);

        // 3. 执行检索
        List<Content> results = textRetriever.retrieve(query);

        // 4. 输出结果
        log.info("✅ [检索完成] 共找到 {} 个相关片段:", results.size());
        for (int i = 0; i < results.size(); i++) {
            Content content = results.get(i);
            TextSegment segment = content.textSegment();

            log.info("--- 片段 [{}] (得分元数据: {}) ---", i + 1, segment.metadata().toMap());
            log.info("内容预览: {}", segment.text().substring(0, Math.min(segment.text().length(), 200)) + "...");
        }
    }

    /**
     * 辅助方法：获取文本检索器
     * 建议：你可以在 AdvancedRagOrchestrator 中添加一个 getter，或者直接在测试类注入
     */
    private ContentRetriever getTextRetriever() {
        // 这里需要根据你项目实际的 Bean 名称来获取
        // 通常可以通过 @Resource(name = "textRetriever") 直接注入到字段中
        return null;
    }
}

