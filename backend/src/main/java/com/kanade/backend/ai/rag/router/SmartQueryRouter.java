package com.kanade.backend.ai.rag.router;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 基于 target 元数据路由。
 * 从共享 Map 中读取 {@link com.kanade.backend.ai.rag.transformer.MultiStrategyQueryTransformer} 写入的 target，
 * 将 vector 查询路由到向量检索器、text 查询路由到全文检索器。
 */
@Slf4j
public class SmartQueryRouter implements QueryRouter {

    private final Map<String, ContentRetriever> retrievers;
    private final Map<String, String> queryTargetMap;

    public SmartQueryRouter(Map<String, ContentRetriever> retrievers,
                            Map<String, String> queryTargetMap) {
        this.retrievers = retrievers;
        this.queryTargetMap = queryTargetMap;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        String target = queryTargetMap.get(query.text());

        if (target != null) {
            return switch (target) {
                case "vector" -> {
                    if (retrievers.containsKey("vector")) {
                        log.info("🎯 [路由] query='{}' → vector", query.text());
                        yield List.of(retrievers.get("vector"));
                    }
                    yield fallback(query);
                }
                case "text" -> {
                    if (retrievers.containsKey("text")) {
                        log.info("🎯 [路由] query='{}' → text", query.text());
                        yield List.of(retrievers.get("text"));
                    }
                    yield fallback(query);
                }
                default -> fallback(query);
            };
        }

            log.info("🎯 [路由决策] 选择了 {} 个检索器: {}",
                selectedRetrievers.size(),
                selectedRetrievers.stream()
                    .map(r -> r.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));

            return selectedRetrievers;
        } catch (Exception e) {
            log.error("❌ [路由失败] 降级使用全部检索器", e);
            return new ArrayList<>(retrievers.values());
        }
    }
