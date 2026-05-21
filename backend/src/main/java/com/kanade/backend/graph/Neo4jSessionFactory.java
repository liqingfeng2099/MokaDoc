package com.kanade.backend.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Neo4j Session 工厂——封装 Session 生命周期管理。
 * 提供模板方法 withSession / withReadSession / withWriteSession，
 * 自动处理 Session 的开启与关闭。
 *
 * @author kanade
 */
@Slf4j
@Component
public class Neo4jSessionFactory {

    private final Driver driver;

    /**
     * 默认数据库名
     */
    private static final String DEFAULT_DATABASE = "neo4j";

    public Neo4jSessionFactory(Driver driver) {
        this.driver = driver;
    }

    /**
     * 读事务模板（自动提交读）。
     */
    public <T> T withReadSession(Function<Session, T> action) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(org.neo4j.driver.AccessMode.READ)
                .withDatabase(DEFAULT_DATABASE)
                .build())) {
            return action.apply(session);
        }
    }

    /**
     * 写事务模板。
     */
    public <T> T withWriteSession(Function<Session, T> action) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(org.neo4j.driver.AccessMode.WRITE)
                .withDatabase(DEFAULT_DATABASE)
                .build())) {
            return action.apply(session);
        }
    }

    /**
     * 写事务模板（无返回值）。
     */
    public void withWriteSession(Consumer<Session> action) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(org.neo4j.driver.AccessMode.WRITE)
                .withDatabase(DEFAULT_DATABASE)
                .build())) {
            action.accept(session);
        }
    }

    /**
     * 验证 Neo4j 连接。
     */
    public boolean verifyConnection() {
        try (Session session = driver.session()) {
            session.run("RETURN 1");
            log.info("✅ Neo4j 连接验证成功");
            return true;
        } catch (Exception e) {
            log.error("❌ Neo4j 连接失败: {}", e.getMessage());
            return false;
        }
    }
}
