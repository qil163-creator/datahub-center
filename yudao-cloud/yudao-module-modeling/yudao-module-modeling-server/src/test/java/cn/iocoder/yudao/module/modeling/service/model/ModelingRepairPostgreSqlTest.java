package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** 使用本测试自建 PostgreSQL 容器，不读取应用配置或接触已有数据库。 */
class ModelingRepairPostgreSqlTest {
    private final cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime =
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone();
    @org.junit.jupiter.api.AfterEach void closeRuntime() { dbSwitchRuntime.close(); }

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void requireDisposableDatabase() throws Exception {
        url = System.getenv("MODELING_TEST_PG_URL");
        if (url != null) {
            assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[1-9][0-9]*/modeling_[a-z0-9_]+(?:[?].*)?"),
                    "仅允许明确指定的本机 modeling_* 一次性测试数据库");
            user = "modeling_test";
            password = "";
        } else {
            assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "无 Docker 或隔离 PostgreSQL");
            POSTGRES.start();
            url = POSTGRES.getJdbcUrl();
            user = POSTGRES.getUsername();
            password = POSTGRES.getPassword();
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void stopContainer() { if (POSTGRES.isRunning()) POSTGRES.stop(); }

    @Test
    void decimal_shouldRoundTripFromDesignThroughDdlAndMetadata() throws Exception {
        ModelDdlExecutor executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", dbSwitchRuntime);
        var conn = new ModelingDatabaseResolver.ConnectionInfo();
        conn.setProductType(ProductTypeEnum.POSTGRESQL);
        conn.setSchema("public");
        ModelFieldItem amount = field("amount", 18, 2);
        ModelFieldItem whole = field("whole", 18, 0);
        ModelFieldItem fraction = field("fraction", 5, 5);
        var fields = List.of(amount, whole, fraction);
        String tableName = "modeling_decimal_contract";
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.modeling_decimal_contract");
            for (String ddl : executor.buildCreateTableDdl(tableName, conn, fields)) statement.execute(ddl);
            var actual = resolver().importFields(1L, "modeling_decimal_contract");
            assertEquals(fields.stream().map(ModelFieldItem::getFieldName).toList(),
                    actual.stream().map(ModelFieldItem::getFieldName).toList());
            for (int index = 0; index < fields.size(); index++) {
                ModelFieldItem expected = fields.get(index);
                ModelFieldItem observed = actual.get(index);
                assertAll(expected.getFieldName(),
                        () -> assertEquals("decimal", SqlTypeSemantics.canonicalName(observed.getDataType())),
                        () -> assertEquals(expected.getDataPrecision(), observed.getDataPrecision()),
                        () -> assertEquals(expected.getDataScale(), observed.getDataScale()),
                        () -> assertEquals(Boolean.TRUE, observed.getNullableFlag()),
                        () -> assertFalse(Boolean.TRUE.equals(observed.getPkFlag())),
                        () -> assertNull(observed.getDataLength()));
            }
        }
    }

    @Test
    void metadata_shouldPreserveNativeTypesAndTimezone() throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.modeling_native_contract");
            statement.execute("CREATE TABLE public.modeling_native_contract (id uuid, document jsonb, local_time timestamp, zoned_time timestamptz)");
        }
        var actual = resolver().importFields(1L, "modeling_native_contract");
        assertEquals(List.of("uuid", "jsonb", "timestamp", "timestamptz"),
                actual.stream().map(ModelFieldItem::getDataType).toList());
        assertNotEquals(SqlTypeSemantics.canonicalName(actual.get(2).getDataType()),
                SqlTypeSemantics.canonicalName(actual.get(3).getDataType()));
    }

    @Test
    void metadataDeadline_shouldInterruptBlockedPostgreSqlRead() throws Exception {
        long begin = System.nanoTime();
        try (var connection = DriverManager.getConnection(url, user, password);
             var deadline = new JdbcReadDeadline(connection, 150);
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("SELECT pg_sleep(5)"));
        }
        assertTrue(System.nanoTime() - begin < 3_000_000_000L, "超时读取未在界限内结束");
    }

    @Test
    void retiredDesignCheckMigrationShouldReplayAndPreserveModelTables() throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            ModelingMigrationTestSupport.resetBaselineTables(connection);
            statement.execute("""
                    CREATE TABLE "datahub-center".system_menu(id bigint PRIMARY KEY, permission text);
                    CREATE TABLE "datahub-center".system_role_menu(id bigint PRIMARY KEY, menu_id bigint);
                    INSERT INTO "datahub-center".system_menu VALUES
                        (1,'modeling:model:check'),(2,'modeling:model:check-config'),(3,'modeling:model:query');
                    INSERT INTO "datahub-center".system_role_menu VALUES (1,1),(2,2),(3,3);
                    """);
            String retirement = Files.readString(ModelingMigrationTestSupport.repoFile(
                    "sql/postgresql/migrations/0139_modeling_drop-design-check.sql"));
            for (int replay = 0; replay < 2; replay++) {
                statement.execute(retirement);
            }
            try (var missing = statement.executeQuery("""
                    SELECT to_regclass('"datahub-center".dh_modeling_model_check'),
                           to_regclass('"datahub-center".dh_modeling_model_check_config'),
                           to_regclass('"datahub-center".dh_modeling_model_check_result')
                    """)) {
                missing.next();
                assertNull(missing.getString(1));
                assertNull(missing.getString(2));
                assertNull(missing.getString(3));
            }
            try (var retained = statement.executeQuery("""
                    SELECT to_regclass('"datahub-center".dh_modeling_logic_model'),
                           (SELECT array_agg(id) FROM "datahub-center".system_menu),
                           (SELECT array_agg(menu_id) FROM "datahub-center".system_role_menu)
                    """)) {
                assertTrue(retained.next());
                assertNotNull(retained.getString(1));
                assertEquals("{3}", retained.getString(2));
                assertEquals("{3}", retained.getString(3));
            }
        }
    }

    private ModelFieldItem field(String name, int precision, int scale) {
        ModelFieldItem field = new ModelFieldItem();
        field.setFieldName(name);
        field.setDataType("decimal");
        field.setDataPrecision(precision);
        field.setDataScale(scale);
        field.setNullableFlag(true);
        return field;
    }

    @Test
    void publishProxy_shouldSuspendOuterTransactionAndRollbackShortCommitAtomically() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.modeling_publish_transaction (id integer primary key, phase integer)");
        jdbc.update("INSERT INTO public.modeling_publish_transaction VALUES (1,0) ON CONFLICT (id) DO UPDATE SET phase=0");
        var models = mock(cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper.class);
        var columns = mock(LogicModelColumnService.class);
        var configValidator = mock(ModelTypeConfigValidator.class);
        var hierarchy = mock(ModelHierarchyResolver.class);
        var model = cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO.builder()
                .id(1L).status("DRAFT").revision(0L).dataDomainId(9L).tableType("DIMENSION").build();
        var column = new ModelFieldItem(); column.setFieldUid("one"); column.setFieldName("name"); column.setDataType("varchar");
        when(columns.listByModelId(1L)).thenReturn(List.of(column));
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), model.getClass());
        when(models.selectById(1L)).thenReturn(model);
        when(models.selectByIdForUpdate(1L)).thenAnswer(invocation -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.queryForObject("SELECT phase FROM public.modeling_publish_transaction WHERE id=1 FOR UPDATE", Integer.class);
            return model;
        });
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(configValidator).validateOnPublish(any(), any(), any());
        when(models.update(any(), any())).thenAnswer(invocation -> jdbc.update(
                "UPDATE public.modeling_publish_transaction SET phase=1 WHERE id=1"));
        // 发布头已真实写入后字段替换失败，整个短提交事务必须回滚。
        doThrow(new IllegalStateException("字段替换失败")).when(columns).replaceByModelId(anyLong(), anyList());
        LogicModelServiceImpl target = new LogicModelServiceImpl();
        ReflectionTestUtils.setField(target, "modelMapper", models);
        ReflectionTestUtils.setField(target, "relationMapper", mock(cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper.class));
        ReflectionTestUtils.setField(target, "columnService", columns);
        ReflectionTestUtils.setField(target, "definitionService", mock(LogicModelDefinitionService.class));
        ReflectionTestUtils.setField(target, "hierarchyResolver", hierarchy);
        ReflectionTestUtils.setField(target, "typeConfigValidator", configValidator);
        ReflectionTestUtils.setField(target, "transactionTemplate", transactions);
        var factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        LogicModelService proxy = (LogicModelService) factory.getProxy();
        transactions.executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> proxy.publishModel(1L, 0L));
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        });
        assertEquals(0, jdbc.queryForObject("SELECT phase FROM public.modeling_publish_transaction WHERE id=1", Integer.class));
        verify(configValidator).validateOnPublish(any(), any(), any());
        verify(columns).replaceByModelId(anyLong(), anyList());
    }

    @Test
    void saveDraftProxyRollsBackHeaderWhenFieldsFailAndRejectsStaleRevision() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.modeling_draft_transaction (id bigint primary key, name text, revision bigint)");
        jdbc.update("INSERT INTO public.modeling_draft_transaction VALUES (1,'before',0) ON CONFLICT(id) DO UPDATE SET name='before',revision=0");
        var models = mock(cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper.class);
        var columns = mock(LogicModelColumnService.class);
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO.class);
        when(models.selectByIdForUpdate(1L)).thenAnswer(invocation -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return jdbc.queryForObject("SELECT name,revision FROM public.modeling_draft_transaction WHERE id=1 FOR UPDATE",
                    (row, ignored) -> cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO.builder()
                            .id(1L).name(row.getString("name")).revision(row.getLong("revision"))
                            .status("DRAFT").build());
        });
        when(models.update(any(), any())).thenAnswer(invocation -> jdbc.update(
                "UPDATE public.modeling_draft_transaction SET name='changed',revision=revision+1 WHERE id=1"));
        // 模型头和当前口径写入之后，真实字段替换失败必须回滚整个草稿事务。
        doThrow(new IllegalStateException("字段替换失败")).when(columns).replaceByModelId(anyLong(), anyList());
        var target = new LogicModelServiceImpl();
        ReflectionTestUtils.setField(target, "modelMapper", models);
        ReflectionTestUtils.setField(target, "relationMapper", mock(cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper.class));
        ReflectionTestUtils.setField(target, "columnService", columns);
        ReflectionTestUtils.setField(target, "definitionService", mock(LogicModelDefinitionService.class));
        ReflectionTestUtils.setField(target, "hierarchyResolver", mock(ModelHierarchyResolver.class));
        ReflectionTestUtils.setField(target, "typeConfigValidator", mock(ModelTypeConfigValidator.class));
        var factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        LogicModelService proxy = (LogicModelService) factory.getProxy();
        var req = new cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO();
        req.setModelId(1L); req.setExpectedRevision(0L); req.setName("changed");
        var field = new ModelFieldItem(); field.setFieldUid("uid-one"); field.setFieldName("renamed"); field.setDataType("varchar");
        req.setFields(List.of(field));
        var failure = assertThrows(IllegalStateException.class, () -> proxy.saveDraft(req));
        assertEquals("字段替换失败", failure.getMessage());
        assertEquals("before", jdbc.queryForObject("SELECT name FROM public.modeling_draft_transaction WHERE id=1", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM public.modeling_draft_transaction WHERE id=1", Long.class));
        verify(models, times(2)).update(any(), any());
        verify(columns).replaceByModelId(anyLong(), anyList());
        clearInvocations(models, columns);
        jdbc.update("UPDATE public.modeling_draft_transaction SET revision=1 WHERE id=1");
        var stale = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> proxy.saveDraft(req));
        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_DESIGN_REVISION_CONFLICT.getCode(), stale.getCode());
        verify(models, never()).update(any(), any());
        verify(columns, never()).replaceByModelId(anyLong(), anyList());
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM public.modeling_draft_transaction WHERE id=1", Long.class));
    }

    private ModelingDatabaseResolver resolver() {
        DatabaseApi api = mock(DatabaseApi.class);
        DatabaseConnectionRespDTO connection = new DatabaseConnectionRespDTO();
        connection.setId(1L);
        connection.setProductType("POSTGRESQL");
        connection.setJdbcUrl(url);
        connection.setUsername(user);
        connection.setPassword(password);
        connection.setSchema("public");
        when(api.getDatabaseConnection(1L)).thenReturn(CommonResult.success(connection));
        ModelingDatabaseResolver resolver = new ModelingDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "dbSwitchRuntime", dbSwitchRuntime);
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        return resolver;
    }
}
