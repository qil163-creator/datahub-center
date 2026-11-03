package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.standardconstraint.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 本轮自有临时 PostgreSQL：真实执行两张表的字段 SQL，不读取业务环境默认连接。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class StandardQualityBindingPostgreSqlTest {
    @Test
    void springProxyMustKeepOneBindingSnapshotEvenInsideReadCommittedCaller() throws Exception {
        String schema = "std_consistency_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user");
        assertNotNull(user);
        try (var fixture = DriverManager.getConnection(url, user, ""); var sql = fixture.createStatement();
             var writer = Executors.newSingleThreadExecutor()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".binding_fixture(version integer,column_name varchar(64))");
                sql.execute("INSERT INTO " + schema + ".binding_fixture VALUES(1,'original_code')");
                var datasource = new DriverManagerDataSource(url, user, "");
                var jdbc = new JdbcTemplate(datasource);
                var transactionManager = new DataSourceTransactionManager(datasource);
                var mappings = mock(StandardMappingMapper.class);
                var columns = mock(MetaColumnMapper.class);
                var tables = mock(MetaTableMapper.class);
                var databases = mock(MetaDbMapper.class);
                var rules = mock(QualityRuleMapper.class);
                var elements = mock(StandardElementMapper.class);
                var materializer = new StandardQualityRuleMaterializer(rules);
                var compilation = new StandardConstraintCompilationService(new StandardConstraintCompiler(),
                        mock(StandardCodeSetMapper.class), mock(StandardCodeItemMapper.class));
                var element = StandardElementDO.builder().id(1).name("标识").columnType("VARCHAR")
                        .nullableFlag(0).status((short) 0).lifecycleStatus("PUBLISHED").build();
                when(elements.selectById(1)).thenReturn(element);
                var compiled = compilation.compile(element, LocalDateTime.now());
                var constraint = compiled.constraints().getFirst();
                when(rules.selectListByStandardElementId(1L)).thenReturn(List.of(QualityRuleDO.builder()
                        .id(20L).standardElementId(1L).status(0)
                        .constraintHash(constraint.constraintHash()).strategyKey(constraint.strategyKey()).build()));
                when(tables.selectById(1L)).thenReturn(MetaTableDO.builder().id(1L).dbId(7L).datasourceId(4L)
                        .tableName("person").objectStatus(1).build());
                when(databases.selectById(7L)).thenReturn(MetaDbDO.builder().id(7L).datasourceId(4L)
                        .dbType("POSTGRESQL").schemaName(schema).objectStatus(1).build());
                var mappingRead = new CountDownLatch(1);
                var changed = new CountDownLatch(1);
                var write = writer.submit(() -> {
                    try {
                        assertTrue(mappingRead.await(5, TimeUnit.SECONDS));
                        sql.execute("UPDATE " + schema + ".binding_fixture SET version=2,column_name='next_code'");
                    } finally { changed.countDown(); }
                    return true;
                });
                when(mappings.selectConfirmedMetadataListByTableId(1L)).thenAnswer(call -> {
                    jdbc.queryForObject("SELECT version FROM " + schema + ".binding_fixture", Integer.class);
                    mappingRead.countDown();
                    assertTrue(changed.await(5, TimeUnit.SECONDS));
                    return List.of(StandardMappingDO.builder().id(30L).elementId(1).columnId(11L).build());
                });
                when(columns.selectById(11L)).thenAnswer(call -> {
                    assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) java.sql.Connection::getTransactionIsolation));
                    return MetaColumnDO.builder().id(11L).tableId(1L).datasourceId(4L).objectStatus(1)
                            .columnName(jdbc.queryForObject("SELECT column_name FROM " + schema + ".binding_fixture", String.class)).build();
                });
                var target = new StandardQualityRuleBindingService(mappings, columns, tables, databases, rules, elements, materializer, compilation);
                var proxyFactory = new ProxyFactory(target);
                proxyFactory.setProxyTargetClass(true);
                proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
                var proxy = (StandardQualityRuleBindingService) proxyFactory.getProxy();
                var outer = new TransactionTemplate(transactionManager);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                var resolved = outer.execute(status -> proxy.resolve(QualityTaskDO.builder().id(99L).taskCategory("TABLE").build(),
                        List.of(QualityCheckObjectDO.builder().id(1L).tableId(1L).datasourceConnId(4L).tableName("person").build()), List.of()));
                assertTrue(write.get(5, TimeUnit.SECONDS));
                assertNotNull(resolved);
                assertTrue(resolved.getFirst().getParamSchema().contains("original_code"));
                assertFalse(resolved.getFirst().getParamSchema().contains("next_code"));
                assertEquals(2, jdbc.queryForObject("SELECT version FROM " + schema + ".binding_fixture", Integer.class));
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test
    void frozenStandardBindingsMustExecuteOnlyTheirOwnPhysicalColumns() throws Exception {
        String schema = "std_binding_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user");
        assertNotNull(user, "必须显式提供自有测试 PostgreSQL 用户");
        try (var connection = DriverManager.getConnection(url, user, ""); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".person (person_code varchar(32))");
                sql.execute("CREATE TABLE " + schema + ".staff (staff_code varchar(32))");
                sql.execute("INSERT INTO " + schema + ".person VALUES(NULL)");
                sql.execute("INSERT INTO " + schema + ".staff VALUES('staff-1')");

                var mappings = mock(StandardMappingMapper.class);
                var columns = mock(MetaColumnMapper.class);
                var tables = mock(MetaTableMapper.class);
                var databases = mock(MetaDbMapper.class);
                var rules = mock(QualityRuleMapper.class);
                var elements = mock(StandardElementMapper.class);
                var materializer = new StandardQualityRuleMaterializer(rules);
                var compilation = new StandardConstraintCompilationService(new StandardConstraintCompiler(),
                        mock(StandardCodeSetMapper.class), mock(StandardCodeItemMapper.class));
                var at = LocalDateTime.of(2026, 10, 16, 0, 0);
                var standard = StandardElementDO.builder().id(1).name("标识").columnType("VARCHAR")
                        .nullableFlag(0).status((short) 0).lifecycleStatus("PUBLISHED").build();
                when(elements.selectById(1)).thenReturn(standard);
                when(elements.selectByIdForUpdate(1)).thenReturn(standard);
                var compiled = compilation.compile(standard, at);
                var templates = new ArrayList<QualityRuleDO>();
                when(rules.insert(any(QualityRuleDO.class))).thenAnswer(call -> {
                    QualityRuleDO rule = call.getArgument(0);
                    rule.setId(20L + templates.size()); rule.setCollectIssueDetail(0); templates.add(rule); return 1;
                });
                when(rules.selectListByStandardElementId(1L)).thenReturn(templates);
                materializer.materialize(standard, compiled);
                assertEquals(1, templates.size());
                when(databases.selectById(7L)).thenReturn(MetaDbDO.builder().id(7L).datasourceId(4L)
                        .dbType("POSTGRESQL").schemaName(schema).objectStatus(1).build());
                var objects = new ArrayList<QualityCheckObjectDO>();
                for (long id : List.of(1L, 2L)) {
                    String name = id == 1 ? "person" : "staff";
                    var object = QualityCheckObjectDO.builder().id(id).datasourceConnId(4L).tableId(id).tableName(name).build();
                    objects.add(object);
                    when(tables.selectById(id)).thenReturn(MetaTableDO.builder().id(id).dbId(7L).datasourceId(4L)
                            .tableName(name).objectStatus(1).build());
                    when(columns.selectById(10L + id)).thenReturn(MetaColumnDO.builder().id(10L + id).tableId(id)
                            .datasourceId(4L).columnName(name + "_code").objectStatus(1).build());
                    when(mappings.selectConfirmedMetadataListByTableId(id)).thenReturn(List.of(StandardMappingDO.builder()
                            .id(30L + id).elementId(1).columnId(10L + id)
                            .targetType("METADATA_COLUMN").mappingStatus("CONFIRMED").build()));
                }
                var binding = new StandardQualityRuleBindingService(mappings, columns, tables, databases,
                        rules, elements, materializer, compilation);
                var executor = QualityRuntimeFixture.executor();
                ReflectionTestUtils.setField(executor, "standardRuleBindings", binding);
                ReflectionTestUtils.setField(executor, "ruleMapper", rules);
                ReflectionTestUtils.setField(executor, "taskRuleMapper", mock(QualityTaskRuleMapper.class));
                var taskObjects = mock(QualityTaskObjectMapper.class);
                when(taskObjects.selectListByTaskId(99L)).thenReturn(List.of(
                        QualityTaskObjectDO.builder().taskId(99L).objectId(1L).build(),
                        QualityTaskObjectDO.builder().taskId(99L).objectId(2L).build()));
                ReflectionTestUtils.setField(executor, "taskObjectMapper", taskObjects);
                var checkObjects = mock(QualityCheckObjectMapper.class);
                when(checkObjects.selectBatchIds(List.of(1L, 2L))).thenReturn(objects);
                ReflectionTestUtils.setField(executor, "checkObjectMapper", checkObjects);
                var task = QualityTaskDO.builder().id(99L).taskCategory("TABLE").build();
                String snapshot = executor.snapshotTaskDefinition(task);
                var definition = JsonUtils.parseObject(snapshot, QualityTaskExecutor.GateDefinition.class);
                assertEquals(2, definition.getRules().size());
                assertTrue(snapshot.contains("executionColumnId"));
                assertTrue(snapshot.contains("standardMappingId"));

                // 冻结后撤销映射，本次执行仍采用冻结绑定；下一次重新冻结则看不到旧绑定。
                when(mappings.selectConfirmedMetadataListByTableId(1L)).thenReturn(List.of());
                when(mappings.selectConfirmedMetadataListByTableId(2L)).thenReturn(List.of());
                var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("POSTGRESQL");
                dto.setJdbcUrl(url); dto.setSchema(schema); dto.setUsername(user); dto.setPassword("");
                dto.setUsageType(1); dto.setConfigurationRevision("fixture-revision");
                var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(CommonResult.success(dto));
                var resolver = new MasterDatabaseResolver(); ReflectionTestUtils.setField(resolver, "databaseApi", api);
                var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
                ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
                ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
                var results = new ArrayList<QualityAuditLogDO>();
                for (var object : definition.getObjects()) {
                    QualityRuntimeFixture.executeObject(executor, 40L, task, route, object, definition.getRules(), results);
                }
                assertEquals(2, results.size(), "禁止把每个标准字段规则交叉套到另一张表");
                assertEquals("person_code", results.getFirst().getCheckField());
                assertEquals(QualityAuditLogDO.RESULT_FAIL, results.getFirst().getResultType(), results.getFirst().getErrorDetail());
                assertEquals(1L, results.getFirst().getFailCount());
                assertEquals("staff_code", results.getLast().getCheckField());
                assertEquals(QualityAuditLogDO.RESULT_PASS, results.getLast().getResultType(), results.getLast().getErrorDetail());
                assertThrows(RuntimeException.class, () -> executor.snapshotTaskDefinition(task));

                // 真实源端字段在冻结后消失时必须留 ERROR，不能当作零问题通过。
                sql.execute("ALTER TABLE " + schema + ".person DROP COLUMN person_code");
                var missingColumn = new ArrayList<QualityAuditLogDO>();
                QualityRuntimeFixture.executeObject(executor, 41L, task, route, objects.getFirst(), definition.getRules(), missingColumn);
                assertEquals(1, missingColumn.size());
                assertEquals(QualityAuditLogDO.RESULT_ERROR, missingColumn.getFirst().getResultType());
                assertNotNull(missingColumn.getFirst().getErrorDetail());
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
