package org.example.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@SpringBootTest
public class DatabaseTest {
    // 自动注入Spring Boot配置的DataSource
    @Autowired
    private DataSource dataSource;

    @Test
    public void testHikariCPInstance() {
        // 验证数据源是否为HikariCP实例（核心验证）
        assert dataSource instanceof HikariDataSource : "数据源不是HikariCP！";
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        // 打印连接池核心信息
        System.out.println("连接池名称：" + hikariDataSource.getPoolName());
        System.out.println("最大连接数：" + hikariDataSource.getMaximumPoolSize());
        System.out.println("最小空闲连接数：" + hikariDataSource.getMinimumIdle());
        System.out.println("HikariCP 版本：" + hikariDataSource.getPoolName());
    }

    @Test
    public void testDatabaseConnection() throws SQLException {
        // 测试获取数据库连接（无异常则表示连接成功）
        try (Connection connection = dataSource.getConnection()) {
            assert connection != null : "数据库连接失败！";
            assert !connection.isClosed() : "数据库连接已关闭！";
            System.out.println("PostgreSQL 连接成功！连接URL：" + connection.getMetaData().getURL());
            System.out.println("数据库版本：" + connection.getMetaData().getDatabaseProductVersion());
        } // try-with-resources 自动关闭连接，归还到连接池
    }
}
