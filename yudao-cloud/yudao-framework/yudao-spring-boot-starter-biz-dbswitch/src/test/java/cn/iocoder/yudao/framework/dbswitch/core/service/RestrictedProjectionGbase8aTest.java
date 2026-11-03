package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 GBase 8a 差异验证。8.6.2.43-R7-free：列名大小写不敏感，反引号是标识符。
 * 默认 sql_mode 含 ANSI_QUOTES；关掉后双引号是字符串。NO_BACKSLASH_ESCAPES 会关闭反斜杠转义。
 * 驱动不在模块依赖里，测试从 {@code datahub.test.gbase8a.driver} 指向的 {@code com.gbase.jdbc.Driver} 加载。
 * 口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
@EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionGbase8aTest {
    private static final String DEFAULT_MODE = "PIPES_AS_CONCAT,ANSI_QUOTES,IGNORE_SPACE,ONLY_FULL_GROUP_BY,"
            + "NO_AUTO_VALUE_ON_ZERO,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,NO_AUTO_CREATE_USER,"
            + "NO_ENGINE_SUBSTITUTION,PAD_CHAR_TO_FULL_LENGTH";
    private static final String WITHOUT_ANSI = "PIPES_AS_CONCAT,IGNORE_SPACE,ONLY_FULL_GROUP_BY,"
            + "NO_AUTO_VALUE_ON_ZERO,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,NO_AUTO_CREATE_USER,"
            + "NO_ENGINE_SUBSTITUTION,PAD_CHAR_TO_FULL_LENGTH";

    private VerifiedDriverTestHandle loader;
    private Connection connection;
    private String database;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.gbase8a.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        database = RestrictedProjectionDifferential.schemaName("q17_");
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GBASE8A);
        var datasource = loader.dataSource(System.getProperty("datahub.test.gbase8a.url"),
                ProductTypeEnum.GBASE8A.getDriveClassName(), "root",
                System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD"), new Properties());
        connection = datasource.getConnection();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            try (var version = sql.executeQuery("SELECT VERSION()")) {
                assertTrue(version.next());
                assertTrue(version.getString(1).contains("8.6.2.43-R7-free"), version.getString(1));
            }
            try (var comment = sql.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
                assertTrue(comment.next());
                assertTrue(comment.getString(2).contains("GBase"), comment.getString(2));
            }
            sql.execute("CREATE DATABASE " + database);
            sql.execute("CREATE TABLE " + database + ".citizen(id INT, name VARCHAR(64), secret VARCHAR(64))");
            sql.execute("INSERT INTO " + database + ".citizen VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + database + ".citizen VALUES (2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try {
            if (connection != null) {
                try (var sql = connection.createStatement()) {
                    if (database != null) {
                        sql.execute("DROP DATABASE IF EXISTS " + database);
                    }
                }
                connection.close();
            }
        } finally {
            if (loader != null) {
                loader.close();
            }
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderQuoteAndEscapeModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.GBASE8A, connection, database, "citizen",
                List.of("SET SESSION sql_mode = '" + DEFAULT_MODE + "'",
                        "SET SESSION sql_mode = '" + WITHOUT_ANSI + "'",
                        "SET SESSION sql_mode = '" + DEFAULT_MODE + ",NO_BACKSLASH_ESCAPES'",
                        "SET SESSION sql_mode = '" + WITHOUT_ANSI + ",NO_BACKSLASH_ESCAPES'"));
        assertEquals(0, accepted);
    }
}
