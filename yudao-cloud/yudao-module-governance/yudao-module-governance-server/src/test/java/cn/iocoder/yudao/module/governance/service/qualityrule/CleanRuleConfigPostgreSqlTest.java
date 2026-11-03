package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual CleanRuleMapper/type-handler coverage, confined to a random schema in an explicit test database. */
@Timeout(30)
class CleanRuleConfigPostgreSqlTest {
    private final String schema = "clean_rule_cfg_" + UUID.randomUUID().toString().replace("-", "");
    private String url;
    private String user;
    private String password;
    private boolean created;
    private UnpooledDataSource source;
    private SqlSessionFactory sessions;

    @BeforeEach
    void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "Explicit isolated datahub.test.postgres.url is required");
        assertTrue(url.matches("jdbc:postgresql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+"),
                "Only an explicitly provided loopback test database is allowed");
        user = System.getProperty("datahub.test.postgres.user", "postgres");
        password = System.getProperty("datahub.test.postgres.password", "");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            sql(connection, "CREATE SCHEMA " + schema);
            created = true;
            sql(connection, "SET search_path TO " + schema);
            sql(connection, "CREATE SEQUENCE dh_governance_clean_rule_seq START 100");
            // Pre-0118 columns use the platform table's real scalar/audit types, including numeric logical deletion.
            sql(connection, """
                    CREATE TABLE dh_governance_clean_rule (
                      id bigint PRIMARY KEY DEFAULT nextval('dh_governance_clean_rule_seq'),
                      name varchar(100) NOT NULL, category_id bigint NOT NULL,
                      rule_level smallint NOT NULL DEFAULT 1, code varchar(32), strategy_key varchar(64),
                      description varchar(500), use_case varchar(500), example varchar(2000),
                      status smallint NOT NULL DEFAULT 0, creator varchar(64) DEFAULT '',
                      create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      deleted smallint NOT NULL DEFAULT 0
                    )
                    """);
            sql(connection, "INSERT INTO dh_governance_clean_rule(id,name,category_id) VALUES(1,'legacy',2)");
            String migration = Files.readString(repo("sql/postgresql/migrations/0118_governance_clean-rule-config.sql"))
                    .replace("\"datahub-center\"", "\"" + schema + "\"");
            sql(connection, migration);
            sql(connection, migration);
        }
        source = new UnpooledDataSource("org.postgresql.Driver",
                url + "?currentSchema=" + schema + "&connectTimeout=5&socketTimeout=10", user, password);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.setDefaultStatementTimeout(10);
        configuration.setEnvironment(new Environment("clean-rule-jsonb", new JdbcTransactionFactory(), source));
        // The production DO's autoResultMap and @TableField(JsonbTypeHandler) provide the mapping under test.
        configuration.addMapper(CleanRuleMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!created) return;
        assertTrue(schema.matches("clean_rule_cfg_[a-f0-9]{32}"), "Only this test's generated schema may be removed");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            sql(connection, "DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void mapperRoundTripsExactStringsAndUnicodeAsJsonbObjectAndPreservesLegacyNull() throws Exception {
        Map<String, Object> first = Map.of("min", "9007199254740993.123456789012345678901",
                "max", "9007199254740994.123456789012345678999", "label", "中文雪❄ / 引号\"与换行\n");
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(CleanRuleMapper.class);
            assertNull(mapper.selectById(1L).getConfigJson(), "0118 must not invent configuration for old rules");
            assertEquals(1, mapper.insert(rule(10L, JsonUtils.toJsonString(first))));
            session.commit();
        }
        assertEquals(first, JsonUtils.parseObject(read(10L).getConfigJson(), Map.class));
        assertStoredObject(10L, first);

        Map<String, Object> updated = Map.of("min", "-9007199254740993.000000000000000000001",
                "max", "999999999999999999999999999.999999999999999999999", "label", "更新后的参数：🚀");
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(CleanRuleMapper.class);
            var value = mapper.selectById(10L);
            value.setConfigJson(JsonUtils.toJsonString(updated));
            assertEquals(1, mapper.updateById(value));
            session.commit();
        }
        assertEquals(updated, JsonUtils.parseObject(read(10L).getConfigJson(), Map.class));
        assertStoredObject(10L, updated);
        assertNull(read(1L).getConfigJson());
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT config_json IS NULL FROM dh_governance_clean_rule WHERE id=1");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
        }
    }

    @Test
    void checkConstraintsRejectArrayAndUtf8OversizeAndMapperRollbackPreservesCommittedValues() throws Exception {
        Map<String, Object> original = Map.of("min", "0.000000000000000000001", "max", "9007199254740993.25",
                "label", "原参数");
        try (var session = sessions.openSession(false)) {
            assertEquals(1, session.getMapper(CleanRuleMapper.class).insert(rule(20L, JsonUtils.toJsonString(original))));
            session.commit();
        }
        String oversized = "{\"label\":\"" + "雪".repeat(22_000) + "\"}";
        assertTrue(oversized.length() < 65_536);
        assertTrue(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536);
        for (String invalid : List.of("[]", oversized)) {
            try (var session = sessions.openSession(false)) {
                var mapper = session.getMapper(CleanRuleMapper.class);
                var value = mapper.selectById(20L);
                value.setName("uncommitted change");
                assertEquals(1, mapper.updateById(value));
                value.setConfigJson(invalid);
                var failure = assertThrows(PersistenceException.class, () -> mapper.updateById(value));
                SQLException rejected = sqlCause(failure);
                assertEquals("23514", rejected.getSQLState());
                assertTrue(rejected.getMessage().contains("dh_clean_rule_config_budget_ck"));
                session.rollback();
            }
            var stored = read(20L);
            assertEquals("精确配置", stored.getName());
            assertEquals(original, JsonUtils.parseObject(stored.getConfigJson(), Map.class));
            assertStoredObject(20L, original);
        }
    }

    private CleanRuleDO read(long id) {
        try (var session = sessions.openSession(true)) {
            return session.getMapper(CleanRuleMapper.class).selectById(id);
        }
    }

    private void assertStoredObject(long id, Map<String, Object> expected) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                SELECT jsonb_typeof(config_json), jsonb_typeof(config_json->'min'),
                       jsonb_typeof(config_json->'max'), config_json->>'min', config_json->>'max', config_json->>'label'
                FROM dh_governance_clean_rule WHERE id=?
                """)) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("object", result.getString(1), "The handler must store an object, not double-encoded JSON text");
                assertEquals("string", result.getString(2));
                assertEquals("string", result.getString(3));
                assertEquals(expected.get("min"), result.getString(4));
                assertEquals(expected.get("max"), result.getString(5));
                assertEquals(expected.get("label"), result.getString(6));
                assertFalse(result.next());
            }
        }
    }

    private static CleanRuleDO rule(long id, String config) {
        var value = CleanRuleDO.builder().id(id).name("精确配置").categoryId(2L).ruleLevel(1)
                .status(CleanRuleDO.STATUS_ENABLE).configJson(config).build();
        value.setCreateTime(LocalDateTime.of(2026, 10, 8, 0, 0));
        value.setUpdateTime(value.getCreateTime());
        value.setCreator("fixture");
        value.setUpdater("fixture");
        return value;
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql;
        }
        throw new AssertionError("Expected a real PostgreSQL constraint failure", failure);
    }

    private static void sql(Connection connection, String statement) throws SQLException {
        try (var command = connection.createStatement()) {
            command.setQueryTimeout(10);
            command.execute(statement);
        }
    }

    private static Path repo(String relative) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root, "Repository migrations must be available to the integration test");
        return root.resolve(relative);
    }
}
