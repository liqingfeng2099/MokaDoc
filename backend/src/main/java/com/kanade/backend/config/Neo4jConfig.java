package com.kanade.backend.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j 原生 Bolt Driver 配置。
 * 不引入 Spring Data Neo4j，仅使用 org.neo4j.driver 原生操作。
 *
 * @author kanade
 */
@Configuration
public class Neo4jConfig {

    @Value("${neo4j.uri}")
    private String uri;

    @Value("${neo4j.username}")
    private String username;

    @Value("${neo4j.password}")
    private String password;

    @Value("${neo4j.pool.max-connection-pool-size:50}")
    private int maxPoolSize;

    @Value("${neo4j.pool.connection-acquisition-timeout:30s}")
    private String acquisitionTimeout;

    @Bean
    public Driver neo4jDriver() {
        Config config = Config.builder()
                .withMaxConnectionPoolSize(maxPoolSize)
                .withConnectionAcquisitionTimeout(parseTimeout(acquisitionTimeout), TimeUnit.SECONDS)
                .build();

        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }

    private long parseTimeout(String timeout) {
        if (timeout == null || timeout.isEmpty()) {
            return 30;
        }
        String num = timeout.replaceAll("[^0-9]", "");
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return 30;
        }
    }
}
