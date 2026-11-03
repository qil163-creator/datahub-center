package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native SQL-standard authorization only; NONE transport is confined to this disposable loopback fixture. */
@EnabledIfSystemProperty(named="datahub.test.hive.url", matches="jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named="datahub.test.hive.driver", matches=".+")
@EnabledIfSystemProperty(named="datahub.test.hive.sqlstd", matches="true")
class AuditHiveReadOnlyHiveTest {
    private HikariDataSource adminPool;
    private HikariDataSource readerPool;
    private Connection admin;
    private Connection reader;
    private String schema;
    private String principal;

    @BeforeEach void setup() throws Exception {
        schema = "q11ha_" + UUID.randomUUID().toString().replace("-", "");
        principal = "q11r_" + UUID.randomUUID().toString().replace("-", "");
        adminPool = pool("hive"); admin = adminPool.getConnection();
        try (var sql = admin.createStatement(); var rows = sql.executeQuery("SET hive.security.authorization.enabled")) {
            assertTrue(rows.next()); assertEquals("hive.security.authorization.enabled=true", rows.getString(1));
        }
        execute(admin, "SET ROLE admin");
        execute(admin, "CREATE DATABASE " + schema);
        execute(admin, "CREATE EXTERNAL TABLE " + table() + "(id BIGINT, part INT, org_code SMALLINT, amount BIGINT) "
                + "STORED AS TEXTFILE TBLPROPERTIES ('external.table.purge'='true')");
        execute(admin, "INSERT INTO " + table() + " VALUES (9007199254740992,1,9,NULL),(9007199254740993,2,10,1)");
        execute(admin, "GRANT SELECT ON TABLE " + table() + " TO USER " + principal);
        readerPool = pool(principal); reader = readerPool.getConnection();
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (reader != null) reader.close(); if (readerPool != null) readerPool.close();
            if (admin != null && schema != null) execute(admin, "DROP DATABASE " + schema + " CASCADE");
        } finally { try { if (admin != null) admin.close(); } finally { if (adminPool != null) adminPool.close(); } }
    }
    private HikariDataSource pool(String user) throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        var driver = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, config)).find(ProductTypeEnum.HIVE).orElseThrow();
        var props = new TargetDataSourceProperties(); props.setTargetProductType(ProductTypeEnum.HIVE);
        props.setUrl(System.getProperty("datahub.test.hive.url")); props.setUsername(user); props.setPassword(""); props.setPoolSize(1);
        return DataSourceUtils.createTargetDataSource(props, null, null, driver);
    }
    @Test void nativeSelectOnlyPrincipalCanReadButCannotWriteAndRevocationTakesEffect() throws Exception {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE);
        assertEquals(List.of("id", "part", "org_code", "amount"), database.queryTableColumnMeta(reader, schema, "sample")
                .stream().map(c -> c.getFieldName()).toList());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.HIVE);
        context.setTableName(table()); context.setColumn("amount"); context.setConfig(Map.of());
        var single = AuditKeySnapshot.read(reader, generator, context, "id", 10, 30);
        var composite = AuditKeySnapshot.readComposite(reader, generator, context, List.of("id", "part"), 10, 30);
        assertTrue(single.complete(), single.reasonCode()); assertTrue(composite.complete(), composite.reasonCode());
        assertEquals(2, single.total()); assertEquals(1, single.fail()); assertEquals(single.total(), composite.total());
        var groups = AuditKeySnapshot.readGroupedComposite(reader, generator, context, List.of("id", "part"), "org_code", 10, 10, 30);
        assertTrue(groups.complete(), groups.reasonCode()); assertEquals(2, groups.groups().size());
        denied(() -> execute(reader, "INSERT INTO " + table() + " VALUES (3,3,9,1)"), "privileges");
        denied(() -> execute(reader, "SET hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager"), "cannot modify hive.txn.manager");
        denied(() -> execute(reader, "SET ROLE admin"), "admin");
        execute(admin, "REVOKE SELECT ON TABLE " + table() + " FROM USER " + principal);
        denied(() -> AuditKeySnapshot.read(reader, generator, context, "id", 10, 30), "privileges");
        var metadataDenied = assertThrows(RuntimeException.class, () -> database.queryTableColumnMeta(reader, schema, "sample"));
        assertFalse(metadataDenied.toString().contains("Connection is closed"));
        replaceClosedReader();
        execute(admin, "GRANT SELECT ON TABLE " + table() + " TO USER " + principal);
        assertTrue(AuditKeySnapshot.read(reader, generator, context, "id", 10, 30).complete());
        assertFalse(reader.isClosed());
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(reader, table()));
    }
    private void denied(org.junit.jupiter.api.function.Executable call, String nativeReason) throws Exception {
        assertFalse(reader.isClosed());
        var failure = assertThrows(SQLException.class, call);
        assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains(nativeReason), failure.getMessage());
        // Hive reports some authorizer failures as 08S01, so Hikari rightly discards that connection.
        // A subsequent "connection closed" must never masquerade as a successful permission denial test.
        replaceClosedReader();
    }
    private void replaceClosedReader() throws SQLException {
        if (reader.isClosed()) { reader.close(); reader = readerPool.getConnection(); }
    }
    @Test void leadingUnderscoreCannotExpandIntoAnotherUnauthorizedTable() throws Exception {
        execute(admin, "CREATE TABLE " + schema + ".`_sample`(id BIGINT) STORED AS TEXTFILE");
        execute(admin, "CREATE TABLE " + schema + ".asample(secret_field STRING) STORED AS TEXTFILE");
        execute(admin, "GRANT SELECT ON TABLE " + schema + ".`_sample` TO USER " + principal);
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE);
        assertEquals(List.of("id"), database.queryTableColumnMeta(reader, schema, "_sample").stream().map(c -> c.getFieldName()).toList());
        assertThrows(RuntimeException.class, () -> database.queryTableColumnMeta(reader, schema, "asample"));
    }
    private String table() { return schema + ".sample"; }
    private static void execute(Connection connection, String text) throws SQLException {
        try (var sql = connection.createStatement()) { sql.setQueryTimeout(120); sql.execute(text); }
    }
}
