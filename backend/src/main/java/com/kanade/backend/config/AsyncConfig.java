package com.kanade.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 * 为图谱构建等耗时任务提供专用线程池，避免占用 Tomcat 请求线程。
 *
 * @author kanade
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /**
     * 图谱构建专用线程池。
     * - 核心线程 2：维持最小并发能力
     * - 最大线程 4：峰值并发（多用户同时构建）
     * - 队列 100：缓冲待处理任务
     * - 拒绝策略 CallerRunsPolicy：队列满时由调用线程执行（保证不丢任务）
     */
    @Bean(name = "graphBuildExecutor")
    public Executor graphBuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("graph-build-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("✅ [异步线程池] graphBuildExecutor 初始化: core=2, max=4, queue=100");
        return executor;
    }
}
