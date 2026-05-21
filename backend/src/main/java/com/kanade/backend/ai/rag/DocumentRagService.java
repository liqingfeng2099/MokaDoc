package com.kanade.backend.ai.rag;

import com.kanade.backend.entity.Document;
import com.kanade.backend.service.DocumentService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DocumentRagService {

    @Value("${file.upload.dir:./uploads/documents}")
    private String uploadDir;

    @Resource
    @Lazy
    private DocumentService documentService;

    @Resource
    private ElasticsearchEmbeddingStore embeddingStore;

    @Resource
    private EmbeddingModel embeddingModel;

    private final Map<Long, Set<Long>> sessionDocumentIds = new ConcurrentHashMap<>();
    private final Set<Long> indexedDocuments = ConcurrentHashMap.newKeySet();

    /**
     * 获取指定会话的文档内容检索器
     */
    public ContentRetriever getContentRetriever(Long sessionId, List<Long> documentIds, Long userId) {
        if (documentIds == null || documentIds.isEmpty()) {
            return null;
        }

        Set<Long> docIdSet = new HashSet<>(documentIds);
        Set<Long> cachedIds = sessionDocumentIds.get(sessionId);

        if (cachedIds != null && cachedIds.equals(docIdSet)) {
            log.info("复用会话 {} 的 RAG 缓存，文档: {}", sessionId, docIdSet);

            return EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .build();
        }

        int newDocs = 0;
        for (Long docId : docIdSet) {
            if (!indexedDocuments.contains(docId)) {
                indexDocument(docId, userId);
                indexedDocuments.add(docId);
                newDocs++;
            }
        }

        sessionDocumentIds.put(sessionId, docIdSet);
        log.info("已构建会话 {} 的 RAG 索引，文档: {}, 新索引: {} 个", sessionId, docIdSet, newDocs);

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .build();
    }

    /**
     * 清除指定会话的 RAG 缓存
     */
    public void clearCache(Long sessionId) {
        sessionDocumentIds.remove(sessionId);
    }

    /**
     * 删除文档的所有向量嵌入
     */
    public void removeDocumentEmbeddings(Long documentId) {
        indexedDocuments.remove(documentId);
        log.info("已从缓存中移除文档索引: docId={}", documentId);
    }

    private void indexDocument(Long docId, Long userId) {
        try {
            Document entity = documentService.getById(docId);
            if (entity == null || entity.getDeleteFlag() == 1) {
                log.warn("文档不存在或已删除: docId={}", docId);
                return;
            }
            if (!entity.getUserId().equals(userId)) {
                log.warn("无权访问文档: docId={}, userId={}", docId, userId);
                return;
            }

            Path filePath = Paths.get(uploadDir, entity.getFilePath());
            if (!Files.exists(filePath)) {
                log.warn("文档文件不存在: {}", filePath);
                return;
            }

            dev.langchain4j.data.document.Document doc =
                    dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument(filePath);

            // 写入es content & vector
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .textSegmentTransformer(segment -> {
                        Metadata metadata = segment.metadata() != null
                                ? segment.metadata().copy()
                                : new Metadata();
                        metadata.put("userId", userId.toString());
                        metadata.put("documentId", docId.toString());
                        return TextSegment.from(segment.text(), metadata);
                    })
                    .build();

            ingestor.ingest(doc);
            log.info("文档已索引到 ES: docId={}, name={}", docId, entity.getName());
        } catch (Exception e) {
            log.error("索引文档失败: docId={}", docId, e);
        }
    }
}
