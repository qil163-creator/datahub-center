package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessTaskDetailPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 生产 MyBatis/JSONB 与表级回调，隔离平台模式；不将模拟 lease 当作恢复认证。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@Timeout(45)
class AccessTypeMappingPostgreSqlTest {
    Connection control;
    String schema;
    DataAccessMapper definitions;
    DataAccessTaskDetailMapper details;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        assertTrue(control.getMetaData().getDatabaseMajorVersion() >= 16);
        schema = "access_map_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_dataforge_access", "dh_dataforge_access_task_detail")) {
            var ddl = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table + "\" \\(.*?\\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
            assertTrue(ddl.find(), table);
            sql(ddl.group().replace("\"datahub-center\".", "").replaceAll(" DEFAULT nextval\\([^\\n]*?::regclass\\)", ""));
        }
        // 基线将发布协议的六个字段以 ALTER 追加，不能只取最初 CREATE 片段来证明当前 DO。
        String runProtocol = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0038_dataforge_run-protocol.sql"));
        var definitionProtocol = Pattern.compile("ALTER TABLE dh_dataforge_access\\b.*?;", Pattern.DOTALL).matcher(runProtocol);
        assertTrue(definitionProtocol.find()); sql(definitionProtocol.group());
        var dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.setEnvironment(new Environment("mapping-test", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(DataAccessMapper.class); config.addMapper(DataAccessTaskDetailMapper.class);
        config.addMapper(DataAccessTaskMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        definitions = session.getMapper(DataAccessMapper.class); details = session.getMapper(DataAccessTaskDetailMapper.class);
        TenantContextHolder.setTenantId(1L);
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (control != null) try (var owned = control) {
            if (schema != null && schema.matches("access_map_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void acceptedTaskConfigurationRoundTripsThroughTheProductionJsonbHandlerAndFrozenRun() throws Exception {
        var config = AccessTypeMappingConfigTest.config();
        config.setTypeMappingAcknowledgements(List.of(AccessTypeMappingConfigTest.decision()));
        var definition = DataAccessDO.builder().id(9007199254740993L).engineType(1).taskName("explicit decision")
                .taskType(2).sourceType(1).sourceDatabaseId(1L).targetDatabaseId(2L).accessMode(1).status(0).dataAccessJson(config).build();
        assertEquals(1, definitions.insert(definition));
        var restored = definitions.selectById(definition.getId());
        assertEquals(config.getTypeMappingAcknowledgements(), restored.getDataAccessJson().getTypeMappingAcknowledgements());
        try (var statement = control.createStatement(); var rows = statement.executeQuery(
                "SELECT jsonb_typeof(data_access_json),data_access_json->'typeMappingAcknowledgements'->0->>'mappingFingerprint' FROM dh_dataforge_access")) {
            assertTrue(rows.next()); assertEquals("object", rows.getString(1)); assertEquals("a".repeat(64), rows.getString(2));
        }
        var frozen = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(restored));
        assertEquals("ods_orders", AccessTaskConfigMapper.toRuntime(frozen.getDataAccessJson(), 2L).snapshot()
                .getTypeMappingAcknowledgements().getFirst().targetTable());
    }

    @Test void callbacksPersistAcceptedAndRejectedPlansSeparatelyFromDriverErrorsAndPageReturnsStructuredDiagnostics() throws Exception {
        var executor = new DataAccessExecutor(); var coordinator = mock(AccessRunCoordinator.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return true; }).when(coordinator).writeOwned(any(), any());
        ReflectionTestUtils.setField(executor, "coordinator", coordinator); ReflectionTestUtils.setField(executor, "taskDetailMapper", details);
        ReflectionTestUtils.setField(executor, "taskMapper", mock(DataAccessTaskMapper.class));
        var definition = DataAccessDO.builder().id(10L).build();
        var run = DataAccessTaskDO.builder().id(20L).executionToken("fixture-token").leaseUntil(LocalDateTime.now().plusMinutes(1)).build();
        for (boolean accepted : List.of(false, true)) {
            var result = new DbSwitchTableResult(); result.setSourceSchemaName("main"); result.setSourceTableName("orders");
            result.setTargetSchemaName("migration"); result.setTargetTableName("ods_orders");
            result.getIfSuccess().set(accepted); result.setErrorMsg("jdbc:fixture-secret-driver-detail");
            result.setTypeMappingPlan(plan(accepted));
            ReflectionTestUtils.invokeMethod(executor, "handleTableResult", definition, run, result, new StringBuffer(),
                    new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
        var service = new DataAccessServiceImpl(); ReflectionTestUtils.setField(service, "taskDetailMapper", details);
        var request = new DataAccessTaskDetailPageReqVO(); request.setTaskId(20L);
        var page = service.getTaskDetailPage(request); assertEquals(2L, page.getTotal()); assertEquals(2, page.getList().size());
        for (var response : page.getList()) {
            boolean accepted = Integer.valueOf(1).equals(response.getIfSuccess());
            assertNotNull(response.getTypeMappingPlan()); assertEquals(accepted, response.getTypeMappingPlan().isAccepted());
            assertEquals(accepted, response.getTypeMappingPlan().getColumns().getFirst().accepted());
            assertEquals(List.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"), response.getTypeMappingPlan().getColumns().getFirst().lossCodes());
            assertFalse(response.toString().contains("jdbc:fixture-secret-driver-detail"));
            assertEquals(accepted ? "同步成功" : null, response.getSuccessMsg());
            assertEquals(accepted ? null : "同步失败；原始驱动消息未持久化，避免泄露连接信息", response.getErrorMsg());
            var stored = details.selectById(response.getId());
            assertTrue(stored.getTypeMappingPlan().contains("mappingFingerprint"));
        }
        try (var statement = control.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM dh_dataforge_access_task_detail WHERE jsonb_typeof(type_mapping_plan)='object'")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
        }
    }

    @Test void historicalNullDiagnosticRemainsNullWithoutReconstructingAPlan() {
        var old = DataAccessTaskDetailDO.builder().id(1L).taskId(20L).dataAccessId(10L).ifSuccess(1).successMsg("legacy").build();
        details.insert(old);
        assertNull(details.selectById(1L).getTypeMappingPlan());
        var response = AccessTypeMappingDiagnostics.detailResponse(details.selectById(1L));
        assertNull(response.getTypeMappingPlan()); assertEquals("legacy", response.getSuccessMsg());
    }

    static TableTypeMappingPlan plan(boolean accepted) {
        var mapping = new ColumnTypeMapping("note", SQLITE3, "TEXT", "TEXT", POSTGRESQL, "text", TypeFidelity.LOSSY,
                List.of(TypeMappingIssue.loss("DECLARED_TYPE_AFFINITY_NOT_STRICT", "SQLite affinity does not constrain every stored value")), "\"note\" text");
        return new TableTypeMappingPlan(POSTGRESQL, List.of(mapping), accepted ? Set.of("note") : Set.of());
    }
    private void sql(String text) throws SQLException { try (var statement = control.createStatement()) { statement.execute(text); } }
}
