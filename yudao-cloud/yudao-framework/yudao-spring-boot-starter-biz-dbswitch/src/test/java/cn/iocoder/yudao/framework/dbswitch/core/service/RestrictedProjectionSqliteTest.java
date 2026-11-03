package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 SQLite 差异验证。文件库，schema 固定为 main。带引号标识符按原文，不带引号大小写不敏感。
 * 双引号是标识符。反斜杠不是转义。没有 sql_mode。
 */
class RestrictedProjectionSqliteTest {
    @TempDir
    Path directory;
    private Connection connection;

    @BeforeEach
    void create() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("q17.db"));
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE citizen(id INTEGER, name TEXT, secret TEXT)");
            sql.execute("INSERT INTO citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.SQLITE3, connection, "main", "citizen",
                List.of("PRAGMA busy_timeout = 1000"));
        assertEquals(0, accepted);
    }
}
