package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="datahub.test.hive.url", matches="jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named="datahub.test.hive.driver", matches=".+")
class HiveMetadataHiveTest {
    private final IDatabaseInterface database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE);
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    private String schema;

    @BeforeAll void setup() throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        var driver = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, config)).find(ProductTypeEnum.HIVE).orElseThrow();
        var props = new TargetDataSourceProperties(); props.setTargetProductType(ProductTypeEnum.HIVE);
        props.setUrl(System.getProperty("datahub.test.hive.url")); props.setUsername("hive"); props.setPassword(""); props.setPoolSize(2);
        pool = DataSourceUtils.createTargetDataSource(props, null, null, driver); connection = pool.getConnection();
        schema = "q11hm_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE DATABASE " + schema);
        // HiveNativeCatalog.read accepts only EXTERNAL_TABLE with TextInputFormat and LazySimpleSerDe.
        externalText("sample_x", "id BIGINT COMMENT 'native id', amount DECIMAL(20,3), name STRING");
        externalText("sampleax", "other_field STRING");
        execute("CREATE VIEW " + schema + ".sample_view AS SELECT id AS `native id`, amount FROM " + schema + ".sample_x");
    }
    /** Hive 4.1.0 ordinary external TEXTFILE. Purge keeps DROP DATABASE CASCADE from leaving the fixture directory. */
    private void externalText(String table, String columns) throws SQLException {
        execute("CREATE EXTERNAL TABLE " + schema + "." + table + "(" + columns
                + ") STORED AS TEXTFILE TBLPROPERTIES ('external.table.purge'='true')");
    }
    private void assertUnsupported(String table, String reason) throws SQLException {
        // Physical column visibility grants no permission to reconstruct a Hive storage model.
        String previous = setting("hive.ddl.output.format"); execute("SET hive.ddl.output.format=text");
        try {
            var failure = assertThrows(SQLFeatureNotSupportedException.class, () ->
                    cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog.read(connection, schema, table, 30));
            assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        } finally { execute("SET " + previous); }
        assertFalse(connection.isClosed());
    }
    @AfterAll void cleanup() throws Exception {
        try { if (connection != null && schema != null) {
            execute("SET hive.support.concurrency=true"); execute("SET hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager");
            execute("DROP DATABASE " + schema + " CASCADE");
        } }
        finally { try { if (connection != null) connection.close(); } finally { if (pool != null) pool.close(); } }
    }
    private void execute(String text) throws SQLException { try (var s = connection.createStatement()) { s.setQueryTimeout(30); s.execute(text); } }
    private String setting(String key) throws SQLException {
        try (var s = connection.createStatement(); var r = s.executeQuery("SET " + key)) {
            assertTrue(r.next()); return r.getString(1);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void physicalNamesAndNativeTypesDoNotRequireChangingCallerSettings(boolean uniqueNames) throws Exception {
        execute("SET hive.resultset.use.unique.column.names=" + uniqueNames);
        var before = List.of(setting("hive.resultset.use.unique.column.names"), setting("hive.support.concurrency"), setting("hive.txn.manager"));
        String catalog = connection.getCatalog();
        var columns = database.queryTableColumnMeta(connection, schema, "sample_x");
        assertEquals(before, List.of(setting("hive.resultset.use.unique.column.names"), setting("hive.support.concurrency"), setting("hive.txn.manager")));
        assertEquals(List.of("id", "amount", "name"), columns.stream().map(c -> c.getFieldName()).toList());
        assertEquals(Types.BIGINT, columns.getFirst().getFieldType()); assertEquals(Long.class.getName(), columns.getFirst().getFiledTypeClassName());
        assertEquals("native id", columns.getFirst().getRemarks());
        assertEquals(20, columns.get(1).getPrecisionSize()); assertEquals(3, columns.get(1).getScaleSize());
        assertEquals(catalog, connection.getCatalog()); assertFalse(connection.isClosed());
        assertEquals(List.of("id", "amount", "name"), database.queryTableColumnMetaOnly(connection, schema, "sample_x")
                .stream().map(c -> c.getFieldName()).toList());
        assertEquals(before.getFirst(), setting("hive.resultset.use.unique.column.names"));
    }
    @Test void metadataReadDoesNotIssueSessionWrites() throws Exception {
        var tracked = spy(connection); var statements = new ArrayList<Statement>();
        doAnswer(call -> {
            var statement = spy(connection.createStatement()); statements.add(statement);
            doAnswer(invocation -> { throw new SQLException("Session writes are not allowed in this metadata call"); })
                    .when(statement).execute(anyString());
            return statement;
        }).when(tracked).createStatement();
        assertEquals(3, database.queryTableColumnMeta(tracked, schema, "sample_x").size());
        assertTrue(statements.stream().allMatch(s -> { try { return s.isClosed(); } catch (SQLException e) { throw new AssertionError(e); } }));
        verify(tracked, never()).close(); verify(tracked, never()).commit(); verify(tracked, never()).rollback();
    }

    @Test void metastoreScalarProfileMatchesActualResultMetadataAndMaterializesExactTypes() throws Exception {
        externalText("scalars", "a TINYINT,b SMALLINT,c INT,d BIGINT,e FLOAT,f DOUBLE,g DECIMAL(20,3),h BOOLEAN,"
                + "i DATE,j TIMESTAMP,k STRING,l BINARY,m CHAR(7),n VARCHAR(40)");
        var columns = database.queryTableColumnMeta(connection, schema, "scalars");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + schema + ".scalars WHERE 1=0")) {
            var actual = rows.getMetaData();
            for (int i = 1; i <= actual.getColumnCount(); i++) {
                var column = columns.get(i - 1);
                assertEquals(actual.getColumnType(i), column.getFieldType(), column.getFieldName());
                assertEquals(actual.getColumnClassName(i), column.getFiledTypeClassName(), column.getFieldName());
                assertEquals(actual.getPrecision(i), column.getPrecisionSize(), column.getFieldName());
                assertEquals(actual.getColumnDisplaySize(i), column.getDisplaySize(), column.getFieldName());
                assertEquals(actual.getScale(i), column.getScaleSize(), column.getFieldName());
                assertEquals(actual.isSigned(i), column.isSigned(), column.getFieldName());
            }
        }
        var evidence = columns.stream().map(c -> c.getMetadataEvidence()).toList();
        var ddl = database.getCreateTableSqlList(columns, List.of(), schema, "materialized", "ordinary ORC", false, Map.of());
        assertEquals(1, ddl.size()); execute(ddl.getFirst());
        assertEquals(evidence, columns.stream().map(c -> c.getMetadataEvidence()).toList());
        assertEquals(columns.stream().map(c -> c.getMetadataEvidence().nativeType().value()).toList(),
                database.queryTableColumnMeta(connection, schema, "materialized").stream().map(c -> c.getMetadataEvidence().nativeType().value()).toList());
        columns.getFirst().setFieldType(Types.VARCHAR);
        assertThrows(UnsupportedOperationException.class, () -> database.getCreateTableSqlList(columns, List.of(), schema, "wrong", null, false, Map.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"text", "json"})
    void metadataAndMaterializationPreserveIntentionalCommentWhitespace(String format) throws Exception {
        String previous = setting("hive.ddl.output.format"); execute("SET hive.ddl.output.format=" + format);
        try {
            String source = "whitespace_" + format, target = "whitespace_copy_" + format;
            externalText(source, "id BIGINT COMMENT '  leading\\nquote\\\' and slash\\\\ trailing  '");
            String expected = "  leading\nquote' and slash\\ trailing  ";
            var columns = database.queryTableColumnMeta(connection, schema, source);
            assertEquals(expected, columns.getFirst().getRemarks());
            for (String ddl : database.getCreateTableSqlList(columns, List.of(), schema, target, null, false, Map.of())) execute(ddl);
            assertEquals(expected, database.queryTableColumnMeta(connection, schema, target).getFirst().getRemarks());
        } finally { execute("SET " + previous); }
    }
    @Test void missingObjectDoesNotReturnAnotherPatternMatch() {
        assertThrows(RuntimeException.class, () -> database.queryTableColumnMeta(connection, schema, "sampl__x"));
        assertFalse(database.getTableDDL(connection, schema, "sample_x").contains("other_field"));
    }
    @Test void viewPhysicalColumnReadDoesNotGrantStorageReconstruction() throws Exception {
        var columns = database.queryTableColumnMeta(connection, schema, "sample_view");
        assertEquals(List.of("native id", "amount"), columns.stream().map(c -> c.getFieldName()).toList());
        assertEquals(20, columns.get(1).getPrecisionSize()); assertEquals(3, columns.get(1).getScaleSize());
        assertUnsupported("sample_view", "views");
        assertTrue(database.getViewDDL(connection, schema, "sample_view").contains("native id"));
    }
    @Test void acidMetadataDoesNotRequireChangingTheCallersTransactionManager() throws Exception {
        try (var creator = pool.getConnection()) {
            try (var sql = creator.createStatement()) {
                sql.setQueryTimeout(30);
                sql.execute("SET hive.support.concurrency=true");
                sql.execute("SET hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager");
                sql.execute("CREATE TABLE " + schema + ".acid_sample(id BIGINT, amount DECIMAL(20,3)) STORED AS ORC TBLPROPERTIES ('transactional'='true')");
            }
        }
        String previousManager = setting("hive.txn.manager"), previousConcurrency = setting("hive.support.concurrency");
        try {
            // Establish the restricted caller session explicitly; the same server also hosts ACID migration tests.
            execute("SET hive.support.concurrency=false");
            execute("SET hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DummyTxnManager");
            String manager = setting("hive.txn.manager"); assertTrue(manager.endsWith("DummyTxnManager"));
            var unavailableQuery = assertThrows(SQLException.class, () -> execute("SELECT * FROM " + schema + ".acid_sample WHERE 1=2"));
            assertTrue(unavailableQuery.getMessage().contains("10265"), unavailableQuery.getMessage());
            var columns = database.queryTableColumnMeta(connection, schema, "acid_sample");
            assertEquals(List.of("id", "amount"), columns.stream().map(c -> c.getFieldName()).toList());
            assertEquals(20, columns.get(1).getPrecisionSize()); assertEquals(3, columns.get(1).getScaleSize());
            assertUnsupported("acid_sample", "ACID");
            assertEquals(manager, setting("hive.txn.manager"));
            assertEquals("hive.support.concurrency=false", setting("hive.support.concurrency"));
        } finally {
            execute("SET " + previousConcurrency);
            execute("SET " + previousManager);
        }
    }
    @Test void partitionColumnsAndComplexNativeTypesAreReadWithoutGrantingStorageReconstruction() throws Exception {
        execute("CREATE TABLE " + schema + ".partitioned(id BIGINT, code CHAR(7), label VARCHAR(40), payload BINARY, items ARRAY<INT>, details STRUCT<id:BIGINT>) "
                + "PARTITIONED BY (region STRING) STORED AS TEXTFILE");
        var columns = database.queryTableColumnMeta(connection, schema, "partitioned");
        assertEquals(List.of("id", "code", "label", "payload", "items", "details", "region"), columns.stream().map(c -> c.getFieldName()).toList());
        assertEquals(7, columns.get(1).getPrecisionSize()); assertEquals(40, columns.get(2).getPrecisionSize());
        assertEquals("array<int>", columns.get(4).getMetadataEvidence().nativeType().value());
        assertEquals("struct<id:bigint>", columns.get(5).getMetadataEvidence().nativeType().value());
        assertUnsupported("partitioned", "partitioned");
    }
    @ParameterizedTest @ValueSource(strings={"text", "json"})
    void escapedPhysicalNamesAndCommentsAreDecodedExactlyOnceWithoutChangingOutputFormat(String format) throws Exception {
        String previous = setting("hive.ddl.output.format");
        String table = "escaped_" + format;
        externalText(table, "`quote\"id` BIGINT COMMENT 'line1\\nline2\\\\path\"quoted\"', `literal\\nid` DECIMAL(20,3)");
        try {
            execute("SET hive.ddl.output.format=" + format);
            var columns = database.queryTableColumnMeta(connection, schema, table);
            assertEquals(List.of("quote\"id", "literal\\nid"), columns.stream().map(c -> c.getFieldName()).toList());
            assertEquals("line1\nline2\\path\"quoted\"", columns.getFirst().getRemarks());
            assertEquals(20, columns.get(1).getPrecisionSize()); assertEquals(3, columns.get(1).getScaleSize());
            assertEquals("hive.ddl.output.format=" + format, setting("hive.ddl.output.format"));
        } finally { execute("SET " + previous); }
    }
    @ParameterizedTest @ValueSource(ints={4096,4097})
    void partitionColumnsCountTowardThePhysicalColumnBudget(int width) throws Exception {
        String table = "wide_" + width;
        String fields = java.util.stream.IntStream.range(0, width - 1).mapToObj(i -> "c" + i + " BIGINT")
                .collect(java.util.stream.Collectors.joining(","));
        execute("CREATE TABLE " + schema + "." + table + "(" + fields + ") PARTITIONED BY (region STRING) STORED AS TEXTFILE");
        String previous = setting("hive.ddl.output.format");
        try {
            for (String format : List.of("text", "json")) {
                execute("SET hive.ddl.output.format=" + format);
                if (width == 4096) assertEquals(width, database.queryTableColumnMeta(connection, schema, table).size());
                else assertThrows(RuntimeException.class, () -> database.queryTableColumnMeta(connection, schema, table));
                assertUnsupported(table, "partitioned");
            }
        } finally { execute("SET " + previous); }
    }
}
