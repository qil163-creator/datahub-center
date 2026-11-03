package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedPublishReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedRepublishReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelDeploymentAttemptDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedLogDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedLogMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 冻结发布版本经物化服务执行真实 PostgreSQL DDL；平台持久化与远程门禁使用受控替身。 */
class ModelMaterializedDatabaseTest {

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL"})
    void incrementSynchronizesColumnsKeysDefaultsAndCommentsPreservesRowsAndCanBeRepeated(ProductTypeEnum product) throws Exception {
        var info = connectionInfo(product);
        var table = uniqueTable();
        var target = info.getSchema() + "." + table;
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            try {
                execute(connection, "CREATE TABLE " + target
                        + " (id bigint PRIMARY KEY, name varchar(20) NOT NULL, legacy text)");
                execute(connection, "INSERT INTO " + target + " VALUES (1, 'preserved', 'legacy value')");
                var note = field("note", "varchar", 80, false);
                note.setDefaultValue("'new default'");
                note.setComment("New note's comment");
                var fixture = fixture(runtime, info, table, List.of(
                        field("id", "bigint", null, false), field("name", "varchar", 200, true), note));

                fixture.service().publish(request(ModelMaterializedDO.MODE_INCREMENT));
                var row = insertedRow(fixture);
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, row.getStatus());
                assertTrue(row.getDdlSql().contains("note"));
                try (var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT id, name, note FROM " + target)) {
                    assertTrue(rows.next());
                    assertEquals(1L, rows.getLong("id"));
                    assertEquals("preserved", rows.getString("name"));
                    assertEquals("new default", rows.getString("note"));
                    assertFalse(rows.next());
                }
                try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, "name")) {
                    assertTrue(columns.next());
                    assertEquals(200, columns.getInt("COLUMN_SIZE"));
                    assertEquals(java.sql.DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"));
                }
                try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, "legacy")) {
                    assertFalse(columns.next());
                }
                try (var keys = connection.getMetaData().getPrimaryKeys(catalog(info), schema(info), table)) {
                    assertTrue(keys.next());
                    assertEquals("name", keys.getString("COLUMN_NAME"));
                    assertFalse(keys.next());
                }
                try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, "note")) {
                    assertTrue(columns.next());
                    assertEquals("New note's comment", columns.getString("REMARKS"));
                }
                try (var tables = connection.getMetaData().getTables(catalog(info), schema(info), table, new String[]{"TABLE"})) {
                    assertTrue(tables.next());
                    assertEquals("Fixture model", tables.getString("REMARKS"));
                }

                when(fixture.materializedMapper().selectById(91L)).thenReturn(row);
                var retry = new ModelMaterializedRepublishReqVO();
                retry.setId(91L);
                retry.setPublishMode(ModelMaterializedDO.MODE_INCREMENT);
                assertEquals(91L, fixture.service().republish(retry));
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, row.getStatus());
                assertTrue(row.getDdlSql().isEmpty());
                assertEquals(1L, countRows(connection, target));
                var logs = ArgumentCaptor.forClass(ModelMaterializedLogDO.class);
                verify(fixture.logMapper(), times(2)).insert(logs.capture());
                assertTrue(logs.getAllValues().stream()
                        .allMatch(log -> ModelMaterializedDO.STATUS_SUCCESS.equals(log.getStatus())));
                assertTrue(logs.getAllValues().get(1).getDdlSql().isEmpty());
            } finally {
                execute(connection, "DROP TABLE IF EXISTS " + target);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL"})
    void rebuildReplacesExistingStructureAndRemovesOldRows(ProductTypeEnum product) throws Exception {
        var info = connectionInfo(product);
        var table = uniqueTable();
        var target = info.getSchema() + "." + table;
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            try {
                execute(connection, "CREATE TABLE " + target + " (id bigint PRIMARY KEY, legacy text)");
                execute(connection, "INSERT INTO " + target + " VALUES (1, 'old row')");
                var fixture = fixture(runtime, info, table,
                        List.of(field("id", "bigint", null, true), field("name", "varchar", 80, false)));

                fixture.service().publish(request(ModelMaterializedDO.MODE_REBUILD));
                var row = insertedRow(fixture);
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, row.getStatus());
                assertEquals(0L, countRows(connection, target));
                try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, "legacy")) {
                    assertFalse(columns.next());
                }
                try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, "name")) {
                    assertTrue(columns.next());
                    assertEquals(80, columns.getInt("COLUMN_SIZE"));
                }
                verify(fixture.logMapper()).insert(any(ModelMaterializedLogDO.class));
            } finally {
                execute(connection, "DROP TABLE IF EXISTS " + target);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL"})
    void incrementCreatesTableWhenTargetDoesNotExist(ProductTypeEnum product) throws Exception {
        var info = connectionInfo(product);
        var table = uniqueTable();
        var target = info.getSchema() + "." + table;
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            try {
                var fixture = fixture(runtime, info, table, List.of(field("id", "bigint", null, true)));
                fixture.service().publish(request(ModelMaterializedDO.MODE_INCREMENT));
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, insertedRow(fixture).getStatus());
                execute(connection, "INSERT INTO " + target + " VALUES (1)");
                assertEquals(1L, countRows(connection, target));
            } finally {
                execute(connection, "DROP TABLE IF EXISTS " + target);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL"})
    void incrementCreationPreservesTemporalDefaultsAndNullabilityAndRepublishHasNoDifference(ProductTypeEnum product) throws Exception {
        var info = connectionInfo(product);
        var table = uniqueTable();
        var target = info.getSchema() + "." + table;
        var businessDate = field("business_date", "date", null, false);
        businessDate.setNullableFlag(false);
        businessDate.setDefaultValue("'2000-01-02'");
        var createdAt = field("created_at", "timestamp", null, false);
        createdAt.setNullableFlag(false);
        createdAt.setDefaultValue("'2000-01-02 03:04:05'");
        var status = field("status", "varchar", 80, false);
        status.setNullableFlag(false);
        status.setDefaultValue("pending");
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            try {
                var fixture = fixture(runtime, info, table,
                        List.of(field("id", "bigint", null, true), businessDate, createdAt, status));
                assertEquals(91L, fixture.service().publish(request(ModelMaterializedDO.MODE_INCREMENT)));
                var row = insertedRow(fixture);
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, row.getStatus(), row.getMessage());
                assertNotNull(row.getDdlSql());
                for (var name : List.of("business_date", "created_at", "status")) {
                    try (var columns = connection.getMetaData().getColumns(catalog(info), schema(info), table, name)) {
                        assertTrue(columns.next(), name);
                        assertEquals(java.sql.DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"), name);
                    }
                }
                // 插入时省略默认字段，验证实库真正应用默认值，而不是只检查生成文本。
                execute(connection, "INSERT INTO " + target + " (id) VALUES (1)");
                assertTemporalDefaults(connection, target);

                when(fixture.materializedMapper().selectById(91L)).thenReturn(row);
                var retry = new ModelMaterializedRepublishReqVO();
                retry.setId(91L);
                retry.setPublishMode(ModelMaterializedDO.MODE_INCREMENT);
                assertEquals(91L, fixture.service().republish(retry));
                assertEquals(ModelMaterializedDO.STATUS_SUCCESS, row.getStatus(), row.getMessage());
                assertTrue(row.getDdlSql().isEmpty());
                assertTemporalDefaults(connection, target);
                var logs = ArgumentCaptor.forClass(ModelMaterializedLogDO.class);
                verify(fixture.logMapper(), times(2)).insert(logs.capture());
                assertTrue(logs.getAllValues().stream()
                        .allMatch(log -> ModelMaterializedDO.STATUS_SUCCESS.equals(log.getStatus())));
                assertNotNull(logs.getAllValues().getFirst().getDdlSql());
                assertTrue(logs.getAllValues().getLast().getDdlSql().isEmpty());
                verify(fixture.materializedMapper()).insert(any(ModelMaterializedDO.class));
            } finally {
                execute(connection, "DROP TABLE IF EXISTS " + target);
            }
        }
    }

    private void assertTemporalDefaults(Connection connection, String target) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, business_date, created_at, status FROM " + target)) {
            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("id"));
            assertEquals(java.time.LocalDate.of(2000, 1, 2), rows.getDate("business_date").toLocalDate());
            assertEquals(java.time.LocalDateTime.of(2000, 1, 2, 3, 4, 5),
                    rows.getTimestamp("created_at").toLocalDateTime());
            assertEquals("pending", rows.getString("status"));
            assertFalse(rows.next());
        }
    }

    private Fixture fixture(DbSwitchRuntime runtime, ModelingDatabaseResolver.ConnectionInfo info,
                            String table, List<ModelFieldItem> fields) {
        var ddl = new ModelDdlExecutor();
        ReflectionTestUtils.setField(ddl, "dbSwitchRuntime", runtime);
        var service = new ModelMaterializedServiceImpl();
        var materialized = mock(ModelMaterializedMapper.class);
        var logs = mock(ModelMaterializedLogMapper.class);
        var models = mock(LogicModelMapper.class);
        var columns = mock(LogicModelColumnService.class);
        var attempts = mock(ModelDeploymentAttemptStore.class);
        var hierarchy = mock(ModelHierarchyResolver.class);
        var names = mock(ModelTableNameResolver.class);
        var databases = mock(ModelingDatabaseResolver.class);
        var model = LogicModelDO.builder().id(1L).name("Fixture model").code(table).layerId(2L)
                .enabled(1).status(LogicModelDO.STATUS_PUBLISHED).tableType("DETAIL").modelStyle("RELATIONAL")
                .dataDomainId(4L).bizCategoryId(5L).build();
        var current = new AtomicReference<ModelMaterializedDO>();
        var nextAttempt = new AtomicLong(100);
        when(models.selectById(1L)).thenReturn(model);
        when(columns.listByModelId(1L)).thenReturn(fields);
        when(names.buildFullTableName(any(), eq(table))).thenReturn(table);
        when(databases.resolve(3L)).thenReturn(info);
        doAnswer(call -> {
            call.getArgument(0, ModelMaterializedDO.class).setId(91L);
            return 1;
        }).when(materialized).insert(any(ModelMaterializedDO.class));
        when(attempts.reserve(any(ModelMaterializedDO.class), any(ModelDeploymentAttemptDO.class))).thenAnswer(call -> {
            var desired = call.getArgument(0, ModelMaterializedDO.class);
            var attempt = call.getArgument(1, ModelDeploymentAttemptDO.class);
            desired.setId(91L);
            if (current.get() == null) { materialized.insert(desired); current.set(desired); }
            else { current.get().setDdlSql(desired.getDdlSql()); }
            current.get().setDesignRevision(model.getRevision()); current.get().setStatus(ModelMaterializedDO.STATUS_RUNNING);
            attempt.setId(nextAttempt.incrementAndGet()); attempt.setMaterializedId(91L);
            attempt.setLeaseOwner("fixture-owner-" + attempt.getId()); attempt.setPhase("PREPARED");
            current.get().setAttemptId(attempt.getId());
            return attempt;
        });
        doAnswer(call -> {
            var attempt = call.getArgument(0, ModelDeploymentAttemptDO.class);
            String status = call.getArgument(1); String message = call.getArgument(2);
            current.get().setStatus(status); current.get().setMessage(message);
            logs.insert(ModelMaterializedLogDO.builder().materializedId(91L).modelId(1L).publishMode(attempt.getPublishMode())
                    .status(status).message(message).ddlSql(attempt.getDdlSql()).build());
            return null;
        }).when(attempts).finish(any(ModelDeploymentAttemptDO.class), any(), any());
        ReflectionTestUtils.setField(service, "materializedMapper", materialized);
        ReflectionTestUtils.setField(service, "logMapper", logs);
        ReflectionTestUtils.setField(service, "logicModelMapper", models);
        ReflectionTestUtils.setField(service, "columnService", columns);
        ReflectionTestUtils.setField(service, "attemptStore", attempts);
        ReflectionTestUtils.setField(service, "typeConfigValidator", mock(ModelTypeConfigValidator.class));
        ReflectionTestUtils.setField(service, "hierarchyResolver", hierarchy);
        ReflectionTestUtils.setField(service, "tableNameResolver", names);
        ReflectionTestUtils.setField(service, "databaseResolver", databases);
        ReflectionTestUtils.setField(service, "ddlExecutor", ddl);
        return new Fixture(service, materialized, logs);
    }

    private ModelMaterializedDO insertedRow(Fixture fixture) {
        var row = ArgumentCaptor.forClass(ModelMaterializedDO.class);
        verify(fixture.materializedMapper()).insert(row.capture());
        return row.getValue();
    }

    private ModelMaterializedPublishReqVO request(String mode) {
        var request = new ModelMaterializedPublishReqVO();
        request.setModelId(1L);
        request.setDatasourceId(3L);
        request.setPublishMode(mode);
        return request;
    }

    private ModelFieldItem field(String name, String type, Integer length, boolean pk) {
        var field = new ModelFieldItem();
        field.setFieldName(name);
        field.setFieldUid("fixture_" + name);
        field.setCnName(name);
        field.setDataType(type);
        field.setDataLength(length);
        field.setPkFlag(pk);
        field.setNullableFlag(!pk);
        return field;
    }

    private ModelingDatabaseResolver.ConnectionInfo connectionInfo(ProductTypeEnum product) {
        var property = product == ProductTypeEnum.POSTGRESQL ? "datahub.test.postgres" : "datahub.test.mysql";
        var url = System.getProperty(property + ".url");
        assumeTrue(url != null && !url.isBlank(), "Real database fixture is required: " + product);
        assumeTrue(url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]+/[A-Za-z0-9_]+(?:\\?.*)?$"),
                "物化DDL专测仅连接本次隔离的本机 PostgreSQL 实例");
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(3L);
        info.setConfigurationRevision("fixture-r1");
        info.setProductType(product);
        info.setJdbcUrl(url);
        info.setUsername(System.getProperty(property + ".user", product == ProductTypeEnum.POSTGRESQL ? "dataforge_test" : "root"));
        info.setPassword("");
        info.setSchema(product == ProductTypeEnum.POSTGRESQL ? "public" : "modeling_test");
        return info;
    }

    private String uniqueTable() {
        return "materialized_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String catalog(ModelingDatabaseResolver.ConnectionInfo info) {
        return info.getProductType() == ProductTypeEnum.MYSQL ? info.getSchema() : null;
    }

    private String schema(ModelingDatabaseResolver.ConnectionInfo info) {
        return info.getProductType() == ProductTypeEnum.POSTGRESQL ? info.getSchema() : null;
    }

    private long countRows(Connection connection, String target) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + target)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private record Fixture(ModelMaterializedServiceImpl service, ModelMaterializedMapper materializedMapper,
                           ModelMaterializedLogMapper logMapper) { }
}
