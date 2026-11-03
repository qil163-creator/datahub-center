package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationIndexPostgreSqlTest {
    private final String sourceSchema = "index_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String targetSchema = "index_dst_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void setup() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + sourceSchema);
            statement.execute("CREATE SCHEMA " + targetSchema);
            statement.execute("CREATE TABLE " + sourceSchema + ".source(id int PRIMARY KEY, \"Tenant\" int NOT NULL,"
                    + " code varchar(40) NOT NULL, email varchar(100), score int, deleted boolean NOT NULL DEFAULT false)");
            statement.execute("CREATE UNIQUE INDEX ux_tenant_code ON " + sourceSchema + ".source(\"Tenant\", code)");
            statement.execute("CREATE INDEX ix_code_tenant ON " + sourceSchema + ".source(code, \"Tenant\")");
            statement.execute("CREATE INDEX ix_score_desc ON " + sourceSchema + ".source(score DESC)");
            statement.execute("CREATE INDEX ix_id_score ON " + sourceSchema + ".source(id, score)");
            statement.execute("CREATE UNIQUE INDEX ux_live_email ON " + sourceSchema + ".source(email) WHERE NOT deleted");
            statement.execute("CREATE INDEX ix_lower_email ON " + sourceSchema + ".source(lower(email))");
            statement.execute("INSERT INTO " + sourceSchema + ".source VALUES"
                    + "(1,10,'a','same@example.com',5,false),(2,10,'b','same@example.com',7,true),(3,20,'a',NULL,NULL,false)");
        }
    }

    @AfterEach void cleanup() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + sourceSchema + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + targetSchema + " CASCADE");
        }
    }

    @Test void copyTargetRebuildsExactIndexesAndSkipsPartialAndExpressionIndexes() throws Exception {
        var request = request();
        request.getTarget().setWriterEngineInsert(false);
        run(request);

        var definitions = indexKeys("copy");
        assertEquals(List.of("(\"Tenant\", code)", "(code, \"Tenant\")", "(id)", "(id, score)", "(score DESC)"),
                List.copyOf(new TreeMap<>(invert(definitions)).keySet()));
        assertTrue(definitions.entrySet().stream().anyMatch(entry -> entry.getValue().equals("UNIQUE (\"Tenant\", code)")));
        assertTrue(definitions.values().stream().noneMatch(keys -> keys.contains("email")),
                "Partial and expression indexes cannot be rebuilt as plain indexes");
        try (var connection = open(); var statement = connection.createStatement()) {
            try (var count = statement.executeQuery("SELECT count(*) FROM " + targetSchema + ".copy")) {
                assertTrue(count.next());
                assertEquals(3, count.getInt(1), "The skipped partial unique index must not block duplicate live/deleted emails");
            }
            var duplicate = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO " + targetSchema + ".copy(id,\"Tenant\",code,deleted) VALUES(4,10,'a',false)"));
            assertEquals("23505", duplicate.getSQLState());
        }
    }

    @Test void insertTargetWithUppercaseNamesQuotesIndexKeysLikeTheCreatedColumns() throws Exception {
        var request = request();
        request.getTarget().setUppercase(true);
        run(request);

        var definitions = indexKeys("COPY");
        assertTrue(definitions.containsValue("UNIQUE (\"TENANT\", \"CODE\")"), definitions::toString);
        assertTrue(definitions.containsValue("(\"SCORE\" DESC)"), definitions::toString);
    }

    @Test void indexNamesAreDeterministicAcrossReplacement() throws Exception {
        run(request());
        var first = indexKeys("copy");
        var replacement = request();
        replacement.getTarget().setTargetDrop(true);
        run(replacement);
        assertEquals(first, indexKeys("copy"));
    }

    /** index name -> "[UNIQUE ](keys)" as reported by pg_indexes. */
    private Map<String, String> indexKeys(String table) throws Exception {
        var result = new TreeMap<String, String>();
        try (var connection = open(); var statement = connection.prepareStatement(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname=? AND tablename=?")) {
            statement.setString(1, targetSchema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String definition = rows.getString(2);
                    String keys = definition.substring(definition.indexOf(" USING btree ") + " USING btree ".length());
                    result.put(rows.getString(1), (definition.startsWith("CREATE UNIQUE INDEX") ? "UNIQUE " : "") + keys);
                }
            }
        }
        return result;
    }

    private static Map<String, String> invert(Map<String, String> definitions) {
        var keys = new TreeMap<String, String>();
        definitions.forEach((name, definition) -> keys.put(definition.replace("UNIQUE ", ""), name));
        return keys;
    }

    private MigrationRequest request() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setUrl(System.getProperty("datahub.test.postgres.url"));
        source.setUsername(user());
        source.setPassword(password());
        source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setSourceSchema(sourceSchema);
        source.setSourceIncludes("source");
        source.setPoolSize(1);
        source.setFetchSize(5);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copy")));
        request.getSource().add(source);
        var target = request.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setUrl(System.getProperty("datahub.test.postgres.url"));
        target.setUsername(user());
        target.setPassword(password());
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setTargetSchema(targetSchema);
        target.setPoolSize(1);
        target.setWriterEngineInsert(true);
        target.setIndexCreate(true);
        return request;
    }

    private static void run(MigrationRequest request) throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            if (!Boolean.TRUE.equals(result.getIfAllSuccess().get())) {
                throw new IllegalStateException("Migration did not succeed: " + result.getTableResultList());
            }
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), user(), password());
    }

    private static String user() {
        return System.getProperty("datahub.test.postgres.user", "dataforge_test");
    }

    private static String password() {
        return System.getProperty("datahub.test.postgres.password", "");
    }
}
