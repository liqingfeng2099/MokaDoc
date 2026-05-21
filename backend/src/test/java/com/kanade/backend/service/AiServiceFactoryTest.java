package com.kanade.backend.service;

import com.kanade.backend.ai.AiModelType;
import com.kanade.backend.ai.AiServiceFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AI服务工厂测试
 */
@Slf4j
@SpringBootTest
public class AiServiceFactoryTest {
    
    @Resource
    private AiServiceFactory aiServiceFactory;
    
    /**
     * 测试获取通用聊天助手
     */
    @Test
    public void testGetChatAssistant() throws InterruptedException {
        log.info("=== 测试通用聊天助手 ===");
        
        Flux<String> response = aiServiceFactory.getChatAssistant()
            .chat(1L, "你好，请介绍一下你自己");
        
        printFluxResponse(response);
    }
    
    /**
     * 测试获取文档分析专家
     */
    @Test
    public void testGetDocumentAnalyst() throws InterruptedException {
        log.info("=== 测试文档分析专家 ===");
        
        String documentContent = """
            Spring Boot是一个用于创建独立的、生产级别的Spring应用程序的框架。
            它简化了Spring应用的初始搭建和开发过程，提供了默认配置和自动装配功能。
            Spring Boot的主要特点包括：
            1. 独立运行的Spring项目
            2. 内嵌Servlet容器
            3. 提供starter简化Maven配置
            4. 自动配置Spring
            5. 提供生产就绪功能，如指标、健康检查和外部化配置
            """;
        
        Flux<String> response = aiServiceFactory.getDocumentAnalyst()
            .analyze(documentContent);
        
        printFluxResponse(response);
    }
    
    /**
     * 测试通过枚举code动态获取服务
     */
    @Test
    public void testDynamicServiceSelection() throws InterruptedException {
        log.info("=== 测试动态服务选择 ===");
        
        // 测试不同的模型类型
        String[] modelTypes = {
            "chat_assistant",
            "document_analyst"
        };
        
        for (String modelType : modelTypes) {
            log.info("切换到模型类型: {}", modelType);
            AiModelType type = AiModelType.getByCode(modelType);
            if (type != null) {
                log.info("成功获取模型: {} - {}", type.getCode(), type.getDescription());
            }
        }
    }
    
    /**
     * 测试缓存机制
     */
    @Test
    public void testServiceCaching() {
        log.info("=== 测试服务缓存 ===");
        
        // 第一次获取，应该创建新实例
        var service1 = aiServiceFactory.getChatAssistant();
        log.info("第一次获取服务实例: {}", System.identityHashCode(service1));
        
        // 第二次获取，应该从缓存返回相同实例
        var service2 = aiServiceFactory.getChatAssistant();
        log.info("第二次获取服务实例: {}", System.identityHashCode(service2));
        
        // 验证是同一个实例
        if (service1 == service2) {
            log.info("✓ 缓存机制正常工作：两次获取的是同一个实例");
        } else {
            log.error("✗ 缓存机制异常：两次获取的是不同实例");
        }
    }
    
    /**
     * 辅助方法：打印Flux响应
     */
    private void printFluxResponse(Flux<String> flux) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullResponse = new StringBuilder();
        
        flux.doOnNext(chunk -> {
            System.out.print(chunk);
            fullResponse.append(chunk);
        })
        .doOnComplete(() -> {
            System.out.println("\n完整响应长度: " + fullResponse.length());
            latch.countDown();
        })
        .doOnError(error -> {
            log.error("响应出错: {}", error.getMessage());
            latch.countDown();
        })
        .subscribe();
        
        // 等待响应完成（最多30秒）
        latch.await(30, TimeUnit.SECONDS);
    }
}
