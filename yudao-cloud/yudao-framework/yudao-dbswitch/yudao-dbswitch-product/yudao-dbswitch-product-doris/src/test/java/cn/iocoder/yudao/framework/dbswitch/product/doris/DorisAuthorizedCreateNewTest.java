package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 产品授权契约必须先于任何连接或目标写入完成。 */
class DorisAuthorizedCreateNewTest {
    private static final List<String> FIELDS = List.of("id", "payload");
    private static final String CREATE = "CREATE TABLE `warehouse`.`output` (`id` BIGINT NOT NULL,"
            + "`payload` VARCHAR(64) NULL) ENGINE=OLAP DUPLICATE KEY(`id`) "
            + "DISTRIBUTED BY HASH(`id`) BUCKETS 1 PROPERTIES('replication_num'='1')";
    private static final String INSERT = DorisAuthorizedCreateNew.insertSql("warehouse", "output", FIELDS);

    @Test void nativePlanIsCanonicalAndDoesNotOpenGeneralScriptParser() {
        var dialect = new DatabaseDorisImpl();
        var plan = dialect.planAuthorizedCreateNew("warehouse", "output", FIELDS, CREATE, INSERT);
        assertEquals(ProductTypeEnum.DORIS, plan.product());
        assertEquals(List.of(0, 1), plan.steps().getLast().parameterOrder());
        assertEquals(plan, dialect.planAuthorizedCreateNew("warehouse", "output", FIELDS,
                plan.steps().getFirst().sql(), plan.steps().getLast().sql()));
        assertFalse(ManagedSchemaScriptPlan.supports(ProductTypeEnum.DORIS));
        assertThrows(UnsupportedOperationException.class, () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.DORIS,
                "warehouse", List.of(CREATE, INSERT)));
    }

    @Test void wrongTargetSqlInjectionPartialRowsAndUnverifiedSemanticsAreRejected() {
        for (String ddl : List.of(CREATE.replace("`warehouse`.`output`", "`warehouse`.`other`"),
                CREATE + "; DROP TABLE `warehouse`.`other`", CREATE.replace("DUPLICATE KEY", "UNIQUE KEY"),
                CREATE.replace("'replication_num'='1'", "'replication_num'='1','storage_policy'='external'"))) {
            assertThrows(IllegalArgumentException.class, () -> DorisAuthorizedCreateNew.plan(
                    "warehouse", "output", FIELDS, ddl, INSERT));
        }
        assertThrows(IllegalArgumentException.class, () -> DorisAuthorizedCreateNew.plan("warehouse", "output",
                List.of("id"), CREATE, DorisAuthorizedCreateNew.insertSql("warehouse", "output", List.of("id"))));
        assertThrows(IllegalArgumentException.class, () -> DorisAuthorizedCreateNew.plan("warehouse", "output",
                FIELDS, CREATE, INSERT.replace("?,?", "?,NULL")));
    }

    @Test void nativeLiteralDefaultsAndCommentsRemainData() {
        String ddl = CREATE.replace("VARCHAR(64) NULL", "VARCHAR(64) NULL DEFAULT 'plain' COMMENT '业务字段'")
                .replace("DISTRIBUTED BY", "COMMENT '数据仓库' DISTRIBUTED BY");
        var plan = DorisAuthorizedCreateNew.plan("warehouse", "output", FIELDS, ddl, INSERT);
        var shape = DorisNativeSchema.parse(plan.steps().getFirst().sql());
        assertEquals("数据仓库", shape.comment());
        assertEquals("业务字段", shape.column("payload").comment());
        assertEquals("'plain'", shape.column("payload").defaultSql());
        assertThrows(IllegalArgumentException.class, () -> DorisAuthorizedCreateNew.plan("warehouse", "output",
                FIELDS, ddl.replace("DEFAULT 'plain'", "DEFAULT uuid()"), INSERT));
    }

    @Test void writerPinsExactObjectProjectionAndParameterOrderBeforeConnecting() {
        var writer = new DorisWriterImpl(new AbstractDataSource() {
            @Override public Connection getConnection() { throw new AssertionError("must not connect"); }
            @Override public Connection getConnection(String user, String password) { return getConnection(); }
        });
        writer.configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().withOlapStreamLoad(false));
        assertEquals(INSERT, writer.planInsertSql("warehouse", "output", FIELDS));
        assertThrows(IllegalArgumentException.class, () -> writer.useAuthorizedInsertSql(
                "warehouse", "output", FIELDS, INSERT, List.of(1, 0)));
        writer.useAuthorizedInsertSql("warehouse", "output", FIELDS, INSERT, List.of(0, 1));
        var plan = DorisAuthorizedCreateNew.plan("warehouse", "output", FIELDS, CREATE, INSERT);
        var altered = new ManagedSchemaScriptPlan(ProductTypeEnum.DORIS, "warehouse", List.of(
                plan.steps().getFirst(), new ManagedSchemaScriptPlan.Step(INSERT, "DML", java.util.Set.of("output"),
                List.of(1, 0), null)));
        assertThrows(IllegalArgumentException.class, () -> writer.executeAuthorizedCreateTable(altered));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareWrite("warehouse", "other", FIELDS));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareWrite("warehouse", "output",
                List.of("payload", "id")));
        assertThrows(IllegalArgumentException.class, () -> writer.useAuthorizedInsertSql(
                "warehouse", "output", FIELDS, INSERT, List.of(0, 1)));
    }

    @Test void onlyPostgresToNewDorisFullJdbcCopyIsAdmitted() {
        var request = request();
        assertDoesNotThrow(() -> MigrationService.validateAuthorizedConfiguration(request));
        request.getTarget().setWriteMode("APPEND");
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(request));
        request.getTarget().setWriteMode("CREATE_NEW");
        request.getTarget().setChangeDataSync(true);
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(request));
        request.getTarget().setChangeDataSync(false);
        request.getSource().getFirst().setSourceProductType(ProductTypeEnum.DORIS);
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(request));
    }

    @Test void onlyLiteralSourceDefaultsMayUseExistingCopyWithoutDefaultMapping() {
        for (String expression : List.of("'ready'::character varying", "42", "-1.25", "true", "NULL")) {
            assertTrue(cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                    .ordinaryPostgresLiteralDefault(expression), expression);
        }
        for (String expression : List.of("now()", "nextval('seq'::regclass)", "1+2", "'x'::custom_type", "1;SELECT 2")) {
            assertFalse(cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                    .ordinaryPostgresLiteralDefault(expression), expression);
        }
    }

    @Test void generatedStructuredDistributionAndReplicaOptionsUseTheSameNativePlan() {
        var id = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
        id.setFieldName("id"); id.setFieldType(java.sql.Types.BIGINT); id.setFieldTypeName("bigint");
        id.setPrecisionSize(19); id.setNullable(false);
        var payload = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
        payload.setFieldName("payload"); payload.setFieldType(java.sql.Types.VARCHAR); payload.setFieldTypeName("varchar");
        payload.setPrecisionSize(64); payload.setDisplaySize(64); payload.setNullable(true);
        var options = new cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions(
                null, "HASH", List.of("id"), "FIXED", 2, 1);
        String ddl = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                new DatabaseDorisImpl(), List.of(id, payload), List.of("id"), "warehouse", "output", true,
                "", false, java.util.Map.of(cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY,
                        options.toJson()));
        var planned = DorisAuthorizedCreateNew.plan("warehouse", "output", FIELDS, ddl, INSERT);
        assertEquals("tag.location.default: 1", DorisNativeSchema.parse(planned.steps().getFirst().sql())
                .properties().get("replication_allocation"));
        assertThrows(IllegalArgumentException.class, () -> DorisAuthorizedCreateNew.plan("warehouse", "output",
                FIELDS, ddl.replace("tag.location.default: 1", "tag.location.remote: 1"), INSERT));
    }

    @Test void configuredKeysCannotInventSourceUniqueness() {
        var mapping = java.util.Map.of("id", "new_id", "label", "label");
        assertDoesNotThrow(() -> cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                .requireNativePrimaryKeys(List.of("id"), mapping, List.of("new_id")));
        assertThrows(UnsupportedOperationException.class, () -> cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                .requireNativePrimaryKeys(List.of("id"), mapping, List.of("label")));
        assertThrows(UnsupportedOperationException.class, () -> cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                .requireNativePrimaryKeys(List.of(), mapping, List.of("label")));
        assertThrows(UnsupportedOperationException.class, () -> cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings
                .requireNativePrimaryKeys(List.of("id", "label"), mapping, List.of("label", "new_id")));
    }

    @Test void unverifiedNativeServerIsRejectedBeforeCreateIsSent() {
        var sql = new java.util.ArrayList<String>();
        var source = new AbstractDataSource() {
            @Override public Connection getConnection() {
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getAutoCommit" -> true;
                            case "close" -> null;
                            case "createStatement" -> java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.Statement.class}, (p, m, a) -> {
                                        if (m.getName().startsWith("execute")) {
                                            sql.add((String) a[0]);
                                            throw new java.sql.SQLException("unverified native server");
                                        }
                                        return null;
                                    });
                            default -> throw new AssertionError(method.getName());
                        });
            }
            @Override public Connection getConnection(String user, String password) { return getConnection(); }
        };
        var writer = new DorisWriterImpl(source);
        writer.configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().withOlapStreamLoad(false));
        writer.useAuthorizedInsertSql("warehouse", "output", FIELDS, INSERT, List.of(0, 1));
        assertThrows(IllegalStateException.class, () -> writer.executeAuthorizedCreateTable(
                DorisAuthorizedCreateNew.plan("warehouse", "output", FIELDS, CREATE, INSERT)));
        assertEquals(List.of("SHOW FRONTENDS"), sql);
    }

    private static MigrationRequest request() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        request.getSource().add(source);
        request.getTarget().setTargetProductType(ProductTypeEnum.DORIS);
        request.getTarget().setWriteMode("CREATE_NEW");
        request.getTarget().setWriterEngineInsert(true);
        return request;
    }
}
