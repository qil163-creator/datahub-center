package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** MariaDB raw row/group evidence is not a substitute for the still-unproven relation generation. */
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/")
class QualityIntegerKeyMariaDbTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), "root", "");
        sql = connection.createStatement(); sql.setQueryTimeout(10);
        schema = "quality_maria_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
    }
    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); }
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "SMALLINT|-32768|32767|5", "INT|-2147483648|2147483647|4", "BIGINT|-9223372036854775808|9223372036854775807|-5"})
    void preservesNativeTypesBoundariesAndGlobalBudgets(String type, String low, String high, int jdbcType) throws Exception {
        create(type); sql.execute("INSERT INTO " + table() + " VALUES(" + low + ",11," + low + ",NULL),(" + high + ",12," + high + ",1)");
        var snapshot = single(); assertTrue(snapshot.complete(), snapshot.reasonCode()); assertEquals(jdbcType, snapshot.jdbcType());
        assertEquals(1, snapshot.fail()); assertEquals(2, snapshot.total());
        assertEquals(snapshot, JsonUtils.parseObject(JsonUtils.toJsonString(snapshot), AuditKeySnapshot.class));
        var tuple = tuple(); assertTrue(tuple.complete(), tuple.reasonCode());
        assertEquals(AuditKeySnapshot.MARIADB_COMPOSITE_ALGORITHM, tuple.algorithm());
        assertEquals(tuple, JsonUtils.parseObject(JsonUtils.toJsonString(tuple), AuditKeySnapshot.class));
        for (boolean composite : new boolean[]{false, true}) {
            var groups = grouped(composite, 2, 2); assertTrue(groups.complete());
            assertEquals(AuditKeySnapshot.MARIADB_INTEGER_GROUP_ENCODING, groups.groupEncoding());
            assertEquals(Set.of(low, high), new HashSet<>(groups.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
            assertTrue(groups.groups().stream().allMatch(group -> group.snapshot().complete()));
            assertEquals("KEY_LIMIT_EXCEEDED", grouped(composite, 1, 2).reasonCode());
            assertEquals("GROUP_LIMIT_EXCEEDED", grouped(composite, 2, 1).reasonCode());
        }
        // Stable counts and unchanged individual column sets do not preserve tuple pairings.
        sql.execute("UPDATE " + table() + " SET part=CASE WHEN part=11 THEN 12 ELSE 11 END, amount=1");
        assertNotEquals(tuple.keyHash(), tuple().keyHash());
        assertEquals(snapshot.keyHash(), single().keyHash());
        assertThrows(SQLFeatureNotSupportedException.class, () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB)
                .queryRelationFingerprint(connection, table()));
    }

    @ParameterizedTest @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "VARCHAR(64)|'9'", "TEXT|'9'", "CHAR(64)|'9'", "ENUM('a','b')|'a'", "SET('a','b')|'a'",
            "DECIMAL(20,0)|9", "BIGINT UNSIGNED|18446744073709551615", "VARBINARY(64)|'9'"})
    void mysqlCompatibleWireTypesDoNotImplyCompatibleIdentity(String type, String value) throws Exception {
        create(type); sql.execute("INSERT INTO " + table() + " VALUES(" + value + ",1," + value + ",NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED", single().reasonCode());
        assertEquals("KEY_TYPE_UNSUPPORTED", tuple().reasonCode());
        for (boolean composite : new boolean[]{false, true}) {
            var groups = grouped(composite, 10, 10);
            assertFalse(groups.complete()); assertEquals("GROUP_KEY_UNSUPPORTED", groups.reasonCode()); assertTrue(groups.groups().isEmpty());
        }
    }

    @Test void nullAndDuplicateKeysCannotProduceProof() throws Exception {
        create("BIGINT"); sql.execute("INSERT INTO " + table() + " VALUES(1,1,9,NULL),(1,1,9,1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL", single().reasonCode()); assertFalse(tuple().complete());
        sql.execute("UPDATE " + table() + " SET id=NULL");
        assertFalse(single().complete()); assertFalse(tuple().complete());
        sql.execute("UPDATE " + table() + " SET org_code=NULL");
        assertFalse(grouped(false, 10, 10).complete()); assertFalse(grouped(true, 10, 10).complete());
    }
    private String table() { return schema + ".sample"; }
    private void create(String type) throws SQLException {
        sql.execute("CREATE TABLE " + table() + "(id " + type + ", part BIGINT, org_code " + type + ", amount BIGINT) ENGINE=InnoDB");
    }
    private AuditRuleContext context() {
        var result = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); result.setProductType(ProductTypeEnum.MARIADB); result.setTableName(table());
        result.setColumn("amount"); result.setConfig(Map.of()); return result;
    }
    private AuditKeySnapshot single() throws SQLException { return AuditKeySnapshot.read(connection, generator, context(), "id", 10, 10); }
    private AuditKeySnapshot tuple() throws SQLException { return AuditKeySnapshot.readComposite(connection, generator, context(), List.of("id", "part"), 10, 10); }
    private AuditKeySnapshot.Grouped grouped(boolean composite, int keys, int groups) throws SQLException {
        return composite ? AuditKeySnapshot.readGroupedComposite(connection, generator, context(), List.of("id", "part"), "org_code", keys, groups, 10)
                : AuditKeySnapshot.readGrouped(connection, generator, context(), "id", "org_code", keys, groups, 10);
    }
}
