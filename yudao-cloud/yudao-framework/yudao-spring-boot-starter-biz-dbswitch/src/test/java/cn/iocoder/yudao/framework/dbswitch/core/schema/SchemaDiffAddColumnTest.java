package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDB2Impl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGbase8aImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHighgoImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOscarImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSybaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Planner text only. No connection is opened and no DROP statement is invented. */
class SchemaDiffAddColumnTest {
    private static final Instant OBSERVED = Instant.parse("2026-10-06T00:00:00Z");

    @ParameterizedTest
    @MethodSource("admittedProducts")
    void plansForwardAddAndDoesNotInventDrop(ProductTypeEnum product, AbstractDatabase database,
                                              MetadataProvenance provenance) throws Exception {
        assertTrue(provenance.nativeProduct(product));
        ColumnDescription column = addition(product, "note");
        DdlPlan plan = new SchemaDiff().planAddColumns(database, "app", "sample", List.of(column), present(provenance));
        assertTrue(plan.ready(), String.valueOf(plan.diagnostics()));
        assertNull(plan.desiredFingerprint());
        assertEquals(1, plan.steps().size());
        DdlPlan.Step step = plan.steps().getFirst();
        assertEquals("step-0001", step.id());
        assertEquals(DdlPlan.Kind.ADD_COLUMN, step.kind());
        assertEquals("note", step.objectName());
        assertEquals(List.of(), step.dependsOn());
        assertEquals(DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED, step.rollbackCompleteness());
        assertTrue(step.risks().contains(DdlPlan.Risk.LOCK));
        assertTrue(step.risks().contains(DdlPlan.Risk.TABLE_REWRITE));
        assertTrue(step.risks().contains(DdlPlan.Risk.REVERSE_CAN_LOSE_DATA));
        assertTrue(step.sql().contains(RestrictedSelectGuard.quotePhysicalIdentifier(product, "app")));
        assertTrue(step.sql().contains(RestrictedSelectGuard.quotePhysicalIdentifier(product, "note")));
        assertTrue(plan.diagnostics().stream().noneMatch(item -> "REVERSE_DROP_UNPROVEN".equals(item.code())));
        if (product == ProductTypeEnum.POSTGRESQL) {
            assertEquals(database.getAddColumnSql("app", "sample", column), step.sql());
            assertEquals("ALTER TABLE " + RestrictedSelectGuard.qualifiedTableName(product, "app", "sample")
                    + " DROP COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(product, "note"), step.reverseSql());
            assertEquals(DdlPlan.Transactionality.TRANSACTIONAL, step.transactionality());
            assertFalse(step.risks().contains(DdlPlan.Risk.IMPLICIT_COMMIT));
        } else {
            assertEquals(callAdd(database, "app", "sample", column), step.sql());
            assertEquals("", step.reverseSql());
            assertFalse(step.sql().toUpperCase(Locale.ROOT).contains("DROP"));
            assertEquals(DdlPlan.Transactionality.IMPLICIT_COMMIT, step.transactionality());
            if (product == ProductTypeEnum.MYSQL) {
                assertTrue(step.risks().contains(DdlPlan.Risk.IMPLICIT_COMMIT));
            } else {
                assertFalse(step.risks().contains(DdlPlan.Risk.IMPLICIT_COMMIT));
            }
            assertAlreadyQuotedNamesAreNotReused(database, product, column, step.sql());
        }
    }

    @Test
    void twoMysqlColumnsStayOrderedWhenReverseIsBlank() {
        DatabaseMysqlImpl database = new DatabaseMysqlImpl();
        DdlPlan plan = new SchemaDiff().planAddColumns(database, "app", "sample",
                List.of(addition(ProductTypeEnum.MYSQL, "note"), addition(ProductTypeEnum.MYSQL, "extra")),
                present(provenance(ProductTypeEnum.MYSQL)));
        assertTrue(plan.ready(), String.valueOf(plan.diagnostics()));
        assertEquals(2, plan.steps().size());
        assertEquals("", plan.steps().getFirst().reverseSql());
        assertEquals("", plan.steps().get(1).reverseSql());
        assertEquals(List.of("step-0001"), plan.steps().get(1).dependsOn());
        assertEquals("extra", plan.steps().get(1).objectName());
    }

    @Test
    void unmetTargetOrColumnFactsDiagnoseAndEmitNoSql() {
        DatabaseMysqlImpl mysql = new DatabaseMysqlImpl();
        MetadataProvenance provenance = provenance(ProductTypeEnum.MYSQL);
        ColumnDescription column = addition(ProductTypeEnum.MYSQL, "note");
        assertDiagnostic(mysql, present(provenance, MetadataValue.value(false), MetadataValue.absent()), column,
                "UNVERIFIED_ADDITIVE_TARGET", "sample");
        assertDiagnostic(mysql, present(MetadataProvenance.unknown(), MetadataValue.value(true), MetadataValue.absent()), column,
                "UNVERIFIED_ADDITIVE_TARGET", "sample");
        assertDiagnostic(mysql, present(provenance, MetadataValue.value(true), MetadataValue.unknown()), column,
                "UNVERIFIED_ADDITIVE_TARGET", "sample");
        assertDiagnostic(mysql, present(provenance, MetadataValue.value(true), MetadataValue.value(List.of("idx"))), column,
                "UNVERIFIED_ADDITIVE_TARGET", "sample");

        ColumnDescription notNull = addition(ProductTypeEnum.MYSQL, "note");
        notNull.setNullable(false);
        assertDiagnostic(mysql, present(provenance), notNull, "ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", "note");
        ColumnDescription key = addition(ProductTypeEnum.MYSQL, "note");
        key.setPk(true);
        assertDiagnostic(mysql, present(provenance), key, "ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", "note");
        ColumnDescription identity = addition(ProductTypeEnum.MYSQL, "note");
        identity.setAutoIncrement(true);
        assertDiagnostic(mysql, present(provenance), identity, "ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", "note");
        ColumnDescription wordNull = addition(ProductTypeEnum.MYSQL, "note");
        wordNull.setDefaultValue("NULL");
        assertDiagnostic(mysql, present(provenance), wordNull, "ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", "note");

        ColumnDescription duplicate = addition(ProductTypeEnum.MYSQL, "note");
        TableSchemaMetadata occupied = new TableSchemaMetadata(List.of(duplicate), List.of(), provenance,
                MetadataValue.value(true), MetadataValue.absent());
        assertDiagnostic(mysql, occupied, addition(ProductTypeEnum.MYSQL, "note"),
                "ADD_REQUIRES_NEW_NULLABLE_NON_KEY_COLUMN", "note");

        ColumnDescription unknown = addition(ProductTypeEnum.MYSQL, "note");
        unknown.setValType(ColumnMetaData.TYPE_NONE);
        assertDiagnostic(mysql, present(provenance), unknown, "UNSUPPORTED_ADDITIVE_COLUMN", "note");

        ColumnDescription badName = addition(ProductTypeEnum.KINGBASE, "bad-name");
        assertDiagnostic(new DatabaseKingbaseImpl(), present(provenance(ProductTypeEnum.KINGBASE)), badName,
                "UNSUPPORTED_ADDITIVE_COLUMN", "bad-name");

        ColumnDescription sibling = addition(ProductTypeEnum.MYSQL, "extra");
        sibling.setNullable(false);
        DdlPlan blocked = new SchemaDiff().planAddColumns(mysql, "app", "sample",
                List.of(addition(ProductTypeEnum.MYSQL, "note"), sibling), present(provenance));
        assertFalse(blocked.ready());
        assertTrue(blocked.steps().isEmpty());
    }

    @Test
    void admittedDialectFeatureRefusalIsDiagnosticOnly() {
        DatabaseMysqlImpl database = new DatabaseMysqlImpl() {
            @Override
            public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
                throw sneaky(new SQLFeatureNotSupportedException("nullable add refused", "0A000"));
            }
        };
        DdlPlan plan = new SchemaDiff().planAddColumns(database, "app", "sample",
                List.of(addition(ProductTypeEnum.MYSQL, "note")), present(provenance(ProductTypeEnum.MYSQL)));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals(1, plan.diagnostics().size());
        assertEquals("UNSUPPORTED_ADDITIVE_COLUMN", plan.diagnostics().getFirst().code());
        assertEquals("note", plan.diagnostics().getFirst().objectName());
    }

    @Test
    void productsWithoutThisAddStayUnverified() {
        assertStaysUnverified(spy(new DatabaseSqlserver2000Impl()), new MetadataProvenance(
                ProductTypeEnum.SQLSERVER2000, "Microsoft SQL Server", "8.00.760",
                "SQLSERVER2000_CATALOG_V1", OBSERVED));
        assertStaysUnverified(spy(new DatabaseInfluxdbImpl()), new MetadataProvenance(
                ProductTypeEnum.INFLUXDB3, "InfluxDB 3 Core", "3.11.5", "INFLUXDB3_CATALOG_V1", OBSERVED));
    }

    private static void assertStaysUnverified(AbstractDatabase database, MetadataProvenance provenance) {
        assertTrue(provenance.nativeProduct(database.getDatabaseType()));
        DdlPlan plan = new SchemaDiff().planAddColumns(database, "app", "sample",
                List.of(addition(database.getDatabaseType(), "note")), present(provenance));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals("UNVERIFIED_ADDITIVE_TARGET", plan.diagnostics().getFirst().code());
        verify(database, never()).getAddColumnSql(any(), any(), any());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> admittedProducts() throws Exception {
        return Stream.of(
                args(ProductTypeEnum.POSTGRESQL, new DatabasePostgresImpl()),
                args(ProductTypeEnum.MYSQL, new DatabaseMysqlImpl()),
                args(ProductTypeEnum.MARIADB, new DatabaseMariaDBImpl()),
                args(ProductTypeEnum.TIDB, new DatabaseTidbImpl()),
                args(ProductTypeEnum.GBASE8A, new DatabaseGbase8aImpl()),
                args(ProductTypeEnum.ORACLE, new DatabaseOracleImpl()),
                args(ProductTypeEnum.DM, new DatabaseDmImpl()),
                args(ProductTypeEnum.KINGBASE, new DatabaseKingbaseImpl()),
                args(ProductTypeEnum.GREENPLUM, new DatabaseGreenplumImpl()),
                args(ProductTypeEnum.HIGHGO, new DatabaseHighgoImpl()),
                args(ProductTypeEnum.UXDB, new DatabaseUxdbImpl()),
                args(ProductTypeEnum.OPENGAUSS, new DatabaseOpengaussImpl()),
                args(ProductTypeEnum.SYBASE, new DatabaseSybaseImpl()),
                args(ProductTypeEnum.SQLSERVER, new DatabaseSqlserverImpl()),
                args(ProductTypeEnum.OCEANBASE, oceanbaseMysql()),
                args(ProductTypeEnum.DORIS, new DatabaseDorisImpl()),
                args(ProductTypeEnum.STARROCKS, new DatabaseStarrocksImpl()),
                args(ProductTypeEnum.CLICKHOUSE, new DatabaseClickhouseImpl()),
                args(ProductTypeEnum.TDENGINE, new DatabaseTdengineImpl()),
                args(ProductTypeEnum.HIVE, new DatabaseHiveImpl()),
                args(ProductTypeEnum.OSCAR, new DatabaseOscarImpl()),
                args(ProductTypeEnum.SQLITE3, new DatabaseSqliteImpl()),
                args(ProductTypeEnum.DB2, new DatabaseDB2Impl()));
    }

    /** Answers only the compatibility probe. No OceanBase server is contacted. */
    private static DatabaseOceanbaseImpl oceanbaseMysql() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(2)).thenReturn("MYSQL");
        DatabaseOceanbaseImpl database = new DatabaseOceanbaseImpl();
        database.prepareDialect(connection);
        return database;
    }

    private static org.junit.jupiter.params.provider.Arguments args(ProductTypeEnum product, AbstractDatabase database) {
        return org.junit.jupiter.params.provider.Arguments.of(product, database, provenance(product));
    }

    private static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case POSTGRESQL -> new MetadataProvenance(product, "PostgreSQL", "18.0", "TEST_NATIVE_CATALOG", OBSERVED);
            case MYSQL -> new MetadataProvenance(product, "MySQL", "8.4.0", "TEST_NATIVE_CATALOG", OBSERVED);
            case MARIADB -> new MetadataProvenance(product, "MariaDB", "11.4.2", "MARIADB_CATALOG_V1", OBSERVED);
            case TIDB -> new MetadataProvenance(product, "TiDB", "v8.5.1", "TIDB_CATALOG_V1", OBSERVED);
            case GBASE8A -> new MetadataProvenance(product, "GBase", "8.6.2.43-R7-free", "GBASE8A_CATALOG_V1", OBSERVED);
            case ORACLE -> new MetadataProvenance(product, "Oracle", "Oracle Database 23ai Free", "ORACLE_CATALOG_V1", OBSERVED);
            case DM -> new MetadataProvenance(product, "DM DBMS", "8.1.5.60", "DM_CATALOG_V1", OBSERVED);
            case KINGBASE -> new MetadataProvenance(product, "KingbaseES", "KingbaseES V008R006C009B0014",
                    "KINGBASE_CATALOG_V1", OBSERVED);
            case GREENPLUM -> new MetadataProvenance(product, "PostgreSQL 12.12 (Greenplum Database 7.1.0",
                    "12.12", "GREENPLUM_CATALOG_V1", OBSERVED);
            case HIGHGO -> new MetadataProvenance(product, "HighGo Database 6 Release 6.0.4.2",
                    "12.7", "HIGHGO_CATALOG_V1", OBSERVED);
            case UXDB -> new MetadataProvenance(product, "UXsinoDB 2.1.0.3", "10.0", "UXDB_CATALOG_V1", OBSERVED);
            case OPENGAUSS -> new MetadataProvenance(product,
                    "(openGauss 7.0.0-RC3 build 01b7e318) compiled at 2026-03-25 18:12:24 commit 0 last mr 9114",
                    "9.2.4", "OPENGAUSS_CATALOG_V1", OBSERVED);
            case SYBASE -> new MetadataProvenance(product, "Adaptive Server Enterprise",
                    "Adaptive Server Enterprise/16.0 SP02 PL02", "SYBASE16_CATALOG_V1", OBSERVED);
            case SQLSERVER -> new MetadataProvenance(product, "Microsoft SQL Server", "16.0",
                    "SQLSERVER16_CATALOG_V1", OBSERVED);
            case OCEANBASE -> new MetadataProvenance(product, "OceanBase", "5.7.25-OceanBase_CE-v4.3.5.6",
                    "OCEANBASE_CATALOG_V1", OBSERVED);
            case DORIS -> new MetadataProvenance(product, "Doris", "doris-4.0.8", "DORIS_CATALOG_V1", OBSERVED);
            case STARROCKS -> new MetadataProvenance(product, "StarRocks", "3.5.21-85fccf8",
                    "STARROCKS_CATALOG_V1", OBSERVED);
            case CLICKHOUSE -> new MetadataProvenance(product, "ClickHouse", "25.8.33.6",
                    "CLICKHOUSE_CATALOG_V1", OBSERVED);
            case TDENGINE -> new MetadataProvenance(product, "TDengine", "3.3.8.8", "TDENGINE_CATALOG_V1", OBSERVED);
            case HIVE -> new MetadataProvenance(product, "Apache Hive",
                    "4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb", "HIVE_CATALOG_V1", OBSERVED);
            case OSCAR -> new MetadataProvenance(product, "OSCAR",
                    "神通数据库管理系统V8.0.251217 for Linux(x86 64bit) (2000 connections) (license invalid after 180 days)",
                    "OSCAR_JDBC_V1", OBSERVED);
            case SQLITE3 -> new MetadataProvenance(product, "SQLite", "3.31.1", "SQLITE_CATALOG_V1", OBSERVED);
            case DB2 -> new MetadataProvenance(product, "DB2/LINUXX8664", "DB2 v12.1.5.0",
                    "DB2_1215_CATALOG_V1", OBSERVED);
            default -> throw new IllegalArgumentException(product.name());
        };
    }

    private static void assertDiagnostic(AbstractDatabase database, TableSchemaMetadata actual, ColumnDescription column,
                                         String code, String objectName) {
        DdlPlan plan = new SchemaDiff().planAddColumns(database, "app", "sample", List.of(column), actual);
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals(code, plan.diagnostics().getFirst().code());
        assertEquals(objectName, plan.diagnostics().getFirst().objectName());
    }

    private static void assertAlreadyQuotedNamesAreNotReused(AbstractDatabase database, ProductTypeEnum product,
                                                              ColumnDescription column, String planned) throws Exception {
        try {
            String quoted = callAdd(database, RestrictedSelectGuard.quotePhysicalIdentifier(product, "app"),
                    RestrictedSelectGuard.quotePhysicalIdentifier(product, "sample"), column);
            assertNotEquals(planned, quoted);
        } catch (IllegalArgumentException | SQLFeatureNotSupportedException rejected) {
            assertFalse(planned.isBlank());
        }
    }

    private static String callAdd(AbstractDatabase database, String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        return database.getAddColumnSql(schema, table, column);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable failure) throws E {
        throw (E) failure;
    }

    private static TableSchemaMetadata present(MetadataProvenance provenance) {
        return present(provenance, MetadataValue.value(true), MetadataValue.absent());
    }

    private static TableSchemaMetadata present(MetadataProvenance provenance, MetadataValue<Boolean> existence,
                                               MetadataValue<List<String>> additional) {
        ColumnDescription id = new ColumnDescription();
        id.setFieldName("id");
        id.setNullable(false);
        return new TableSchemaMetadata(List.of(id), List.of("id"), provenance, existence, additional);
    }

    private static ColumnDescription addition(ProductTypeEnum product, String name) {
        if (product == ProductTypeEnum.ORACLE) {
            ColumnDescription column = new ColumnDescription();
            column.setFieldName(name);
            column.setFieldType(Types.VARCHAR);
            column.setDisplaySize(20);
            column.setNullable(true);
            column.setDbType(ProductTypeEnum.ORACLE);
            return column;
        }
        if (product == ProductTypeEnum.DM) {
            ColumnDescription column = new ColumnDescription();
            column.setFieldName(name);
            column.setDbType(ProductTypeEnum.DM);
            column.setFieldType(Types.VARCHAR);
            column.setValType(ColumnMetaData.TYPE_STRING);
            column.setLength(20);
            column.setPrecision(0);
            column.setNullable(true);
            return column;
        }
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(product);
        column.setValType(ColumnMetaData.TYPE_STRING);
        column.setLength(20);
        column.setPrecision(0);
        column.setNullable(true);
        // Hive and ClickHouse type text follows the JDBC type, not the logical type alone.
        if (product == ProductTypeEnum.HIVE || product == ProductTypeEnum.CLICKHOUSE) {
            column.setFieldType(Types.VARCHAR);
        }
        return column;
    }
}
