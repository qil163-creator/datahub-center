package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApiImpl;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.*;
import cn.iocoder.yudao.module.governance.service.standardconstraint.*;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingCommandService;
import cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从真实命名空间枚举、生产采集落库到落标冻结/执行，不人工构造空 schema 或猜 public。 */
@EnabledIfEnvironmentVariable(named = "METADATA_WRITE_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-zA-Z0-9_]+")
@Timeout(40)
class MetaCollectedNamespacePostgreSqlTest {
    @Test
    void discoveredNonDefaultSchemaMustSurviveCollectionApiMappingAndQualityExecution() throws Exception {
        String url = System.getenv("METADATA_WRITE_TEST_JDBC_URL");
        String user = System.getenv("METADATA_WRITE_TEST_JDBC_USER");
        String password = System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_PASSWORD", "");
        var source = new DriverManagerDataSource(url, user, password);
        var control = new JdbcTemplate(source);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String physicalSchema = "observed_ns_" + suffix;
        String platformSchema = "platform_ns_" + suffix;
        Object oldBeanFactory = ReflectionTestUtils.getField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory");
        control.execute("CREATE SCHEMA " + physicalSchema);
        control.execute("CREATE SCHEMA " + platformSchema);
        try {
            control.execute("CREATE TABLE " + physicalSchema + ".person(person_code varchar(32))");
            control.execute("INSERT INTO " + physicalSchema + ".person VALUES(NULL)");
            SingleTenantPostgreSqlFixture.copyCurrentTables(control, platformSchema, List.of(
                    "dh_governance_meta_db", "dh_governance_meta_table", "dh_governance_meta_column",
                    "dh_governance_meta_table_snapshot", "dh_governance_meta_change",
                    "dh_governance_standard_element",
                    "dh_governance_standard_mapping", "dh_governance_quality_rule"));
            var platform = new DriverManagerDataSource(url + "?currentSchema=" + platformSchema, user, password);
            var tx = new DataSourceTransactionManager(platform);
            var beans = new DefaultListableBeanFactory(); beans.registerSingleton("dataSource", platform);
            new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(beans);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setEnvironment(new Environment("namespace-test", new SpringManagedTransactionFactory(), platform));
            GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
            for (Class<?> mapper : List.of(MetaDbMapper.class, MetaTableMapper.class, MetaColumnMapper.class,
                    MetaTableSnapshotMapper.class, MetaChangeMapper.class, StandardElementMapper.class,
                    StandardMappingMapper.class, QualityRuleMapper.class)) config.addMapper(mapper);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var databases = session.getMapper(MetaDbMapper.class);
            var tables = session.getMapper(MetaTableMapper.class);
            var columns = session.getMapper(MetaColumnMapper.class);
            var elements = session.getMapper(StandardElementMapper.class);
            var mappings = session.getMapper(StandardMappingMapper.class);
            var rules = session.getMapper(QualityRuleMapper.class);
            var command = new StandardMappingCommandService();
            ReflectionTestUtils.setField(command, "mappingMapper", mappings);
            ReflectionTestUtils.setField(command, "elementMapper", elements);
            ReflectionTestUtils.setField(command, "columnMapper", columns);
            var commandProxy = proxy(command, tx);
            var engine = new MetaDiffEngine();
            ReflectionTestUtils.setField(engine, "dbMapper", databases);
            ReflectionTestUtils.setField(engine, "tableMapper", tables);
            ReflectionTestUtils.setField(engine, "columnMapper", columns);
            ReflectionTestUtils.setField(engine, "snapshotMapper", session.getMapper(MetaTableSnapshotMapper.class));
            ReflectionTestUtils.setField(engine, "changeMapper", session.getMapper(MetaChangeMapper.class));
            ReflectionTestUtils.setField(engine, "transactionTemplate", new TransactionTemplate(tx));
            ReflectionTestUtils.setField(engine, "mappingCommandService", commandProxy);
            ReflectionTestUtils.setField(engine, "env", "namespace-test");
            var context = new MetaCollectContext(); context.setDatasourceId(4L); context.setInstanceId(8L);
            context.setDbType("POSTGRESQL"); context.setLogger(mock(MetaCollectLogger.class));
            var reader = new MetaDataByDataSourceServiceImpl(source, ProductTypeEnum.POSTGRESQL);
            String observed = reader.querySchemaList().stream().filter(physicalSchema::equals).findFirst().orElseThrow();
            var collector = new MetaCollectExecutor();
            ReflectionTestUtils.setField(collector, "diffEngine", engine);
            ReflectionTestUtils.setField(collector, "transactionTemplate", new TransactionTemplate(tx));
            var failures = new ArrayList<java.util.Map<String, String>>();
            ReflectionTestUtils.invokeMethod(collector, "processSchema", context, reader,
                    MetaCollectTaskDO.builder().scopeType(MetaCollectTaskDO.SCOPE_ALL).build(), observed, null, List.of(), failures);
            assertTrue(failures.isEmpty(), failures.toString());
            var database = databases.selectByNaturalKey(4L, observed, observed);
            assertNotNull(database, "采集必须精确保存实际用于读表的命名空间");
            var table = tables.selectByDbIdAndName(database.getId(), "person");
            var column = columns.selectByTableIdAndName(table.getId(), "person_code");
            assertNotNull(column);
            var api = new MetadataApiImpl();
            ReflectionTestUtils.setField(api, "dbMapper", databases);
            ReflectionTestUtils.setField(api, "tableMapper", tables);
            var consumed = api.getTable(table.getId()).getCheckedData();
            assertEquals(observed, consumed.getSchemaName());
            assertNotEquals("public", consumed.getSchemaName());

            var compilation = new StandardConstraintCompilationService(new StandardConstraintCompiler(),
                    mock(StandardCodeSetMapper.class), mock(StandardCodeItemMapper.class));
            var standard = StandardElementDO.builder().code("DE_NAMESPACE").name("标识").engName("person_code")
                    .columnType("VARCHAR").nullableFlag(0).lifecycleStatus("PUBLISHED").status((short) 0).build();
            elements.insert(standard);
            var compiled = compilation.compile(standard, LocalDateTime.now());
            var materializer = new StandardQualityRuleMaterializer(rules);
            materializer.materialize(standard, compiled);
            var storedTemplates = rules.selectListByStandardElementId(standard.getId().longValue());
            assertEquals(1, storedTemplates.size());
            var storedParameters = JsonUtils.parseObject(storedTemplates.getFirst().getParamSchema(), java.util.Map.class);
            assertEquals(Boolean.FALSE, storedParameters.get("allowBlank"), "必须通过生产Mapper完整回读JSONB参数");
            assertFalse(storedParameters.containsKey("column"), "抽象模板不得被实际物理字段污染");
            commandProxy.confirm("METADATA_COLUMN", column.getResourceUrn(), standard.getId(), column.getId(),
                    "MANUAL", "采集后的明确落标", column.getColumnName());
            var executionMaterializer = spy(materializer);
            var firstInMaterializer = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondInMaterializer = new CountDownLatch(1);
            var materializations = new AtomicInteger();
            doAnswer(invocation -> {
                if (materializations.incrementAndGet() == 1) {
                    firstInMaterializer.countDown();
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } else {
                    secondInMaterializer.countDown();
                }
                return invocation.callRealMethod();
            }).when(executionMaterializer).materialize(any(), any());
            var binding = proxy(new StandardQualityRuleBindingService(mappings, columns, tables, databases,
                    rules, elements, executionMaterializer, compilation), tx);
            var object = QualityCheckObjectDO.builder().id(9L).datasourceConnId(4L).tableId(table.getId())
                    .tableName("person").build();
            var task = QualityTaskDO.builder().id(10L).taskCategory("TABLE").build();
            // 两个任务同时首次使用新约束时，只能产生一组模板，且已有停用规则不能被重启。
            var disabled = storedTemplates.getFirst();
            rules.updateById(QualityRuleDO.builder().id(disabled.getId())
                    .status(QualityRuleDO.STATUS_DISABLE).build());
            elements.updateById(StandardElementDO.builder().id(standard.getId()).dataLength(8).build());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> {
                    return binding.resolveForExecution(task, List.of(object), List.of());
                });
                assertTrue(firstInMaterializer.await(5, TimeUnit.SECONDS));
                var second = pool.submit(() -> {
                    return binding.resolveForExecution(task, List.of(object), List.of());
                });
                try {
                    assertFalse(secondInMaterializer.await(500, TimeUnit.MILLISECONDS), "同一标准的第二个任务必须等待持锁事务");
                } finally {
                    releaseFirst.countDown();
                }
                assertEquals(1, first.get(10, TimeUnit.SECONDS).size());
                assertEquals(1, second.get(10, TimeUnit.SECONDS).size());
            }
            var currentTemplates = rules.selectListByStandardElementId(standard.getId().longValue());
            assertEquals(2, currentTemplates.size());
            assertEquals(QualityRuleDO.STATUS_DISABLE, rules.selectById(disabled.getId()).getStatus());
            rules.updateById(QualityRuleDO.builder().id(disabled.getId())
                    .status(QualityRuleDO.STATUS_ENABLE).build());
            new JdbcTemplate(platform).update("UPDATE dh_governance_standard_element SET data_length = NULL WHERE id = ?",
                    standard.getId());
            reset(executionMaterializer);
            var executor = QualityRuntimeFixture.executor();
            ReflectionTestUtils.setField(executor, "standardRuleBindings", binding);
            ReflectionTestUtils.setField(executor, "ruleMapper", rules);
            ReflectionTestUtils.setField(executor, "taskRuleMapper", mock(QualityTaskRuleMapper.class));
            var taskObjects = mock(QualityTaskObjectMapper.class);
            when(taskObjects.selectListByTaskId(10L)).thenReturn(List.of(QualityTaskObjectDO.builder().taskId(10L).objectId(9L).build()));
            ReflectionTestUtils.setField(executor, "taskObjectMapper", taskObjects);
            var checkObjects = mock(QualityCheckObjectMapper.class);
            when(checkObjects.selectBatchIds(List.of(9L))).thenReturn(List.of(object));
            ReflectionTestUtils.setField(executor, "checkObjectMapper", checkObjects);
            var definition = JsonUtils.parseObject(executor.snapshotTaskDefinition(task), QualityTaskExecutor.GateDefinition.class);
            assertEquals(observed, definition.getRules().getFirst().getExecutionSchemaName());
            assertEquals(column.getId(), definition.getRules().getFirst().getExecutionColumnId());
            definition.getRules().forEach(rule -> rule.setCollectIssueDetail(0));
            var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("POSTGRESQL");
            dto.setJdbcUrl(url); dto.setSchema(observed); dto.setUsername(user); dto.setPassword(password);
            dto.setUsageType(1); dto.setConfigurationRevision("namespace-fixture");
            var databaseApi = mock(DatabaseApi.class); when(databaseApi.getDatabaseConnection(4L)).thenReturn(CommonResult.success(dto));
            var resolver = new MasterDatabaseResolver(); ReflectionTestUtils.setField(resolver, "databaseApi", databaseApi);
            ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
            ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
            ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
            var result = new ArrayList<QualityAuditLogDO>();
            QualityRuntimeFixture.executeObject(executor, 11L, task, resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS),
                    object, definition.getRules(), result);
            assertEquals(1, result.size());
            assertEquals(QualityAuditLogDO.RESULT_FAIL, result.getFirst().getResultType(), result.getFirst().getErrorDetail());
            assertEquals("person_code", result.getFirst().getCheckField());
            assertEquals(1L, result.getFirst().getFailCount());
            assertEquals(database.getId(), engine.upsertDb(context, observed).getId(), "重复采集必须保留命名空间身份");
        } finally {
            ReflectionTestUtils.setField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory", oldBeanFactory);
            control.execute("DROP SCHEMA IF EXISTS " + physicalSchema + " CASCADE");
            control.execute("DROP SCHEMA IF EXISTS " + platformSchema + " CASCADE");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, DataSourceTransactionManager manager) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
