package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaColumnUpdateBizReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableUpdateBizReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableFinalizeReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableStateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingCommandService;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaDiffEngine;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectContext;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectLogger;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 回环 PostgreSQL、生产 Mapper 和 Spring 代理事务验证维护合同及并发覆盖防护。 */
@EnabledIfEnvironmentVariable(named = "METADATA_WRITE_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-zA-Z0-9_]+")
@Timeout(40)
class MetadataMaintenancePostgreSqlTest {
    private JdbcTemplate control;
    private JdbcTemplate jdbc;
    private String schema;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactionManager;
    private MetaObjectService objects;
    private MetaObjectServiceImpl objectTarget;
    private MetaTagService tags;
    private StandardMappingCommandService mappings;
    private Object previousBeanFactory;
    private boolean beanFactoryInstalled;

    @BeforeEach
    void setup() {
        String url = System.getenv("METADATA_WRITE_TEST_JDBC_URL");
        String user = System.getenv("METADATA_WRITE_TEST_JDBC_USER");
        String password = System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_PASSWORD", "");
        control = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "metadata_write_test_" + UUID.randomUUID().toString().replace("-", "");
        control.execute("CREATE SCHEMA " + schema);
        SingleTenantPostgreSqlFixture.copyCurrentTables(control, schema, List.of(
                "dh_governance_meta_db", "dh_governance_meta_table", "dh_governance_meta_column",
                "dh_governance_meta_tag", "dh_governance_meta_tag_rel", "dh_governance_meta_change",
                "dh_governance_meta_table_snapshot",
                "dh_governance_standard_element",
                "dh_governance_standard_mapping"));
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        previousBeanFactory = ReflectionTestUtils.getField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory");
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("dataSource", source);
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(beans);
        beanFactoryInstalled = true;
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("metadata-write-test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        for (Class<?> mapper : List.of(MetaDbMapper.class, MetaTableMapper.class, MetaColumnMapper.class,
                MetaTagMapper.class, MetaTagRelMapper.class, MetaChangeMapper.class,
                MetaTableSnapshotMapper.class,
                StandardElementMapper.class, StandardMappingMapper.class)) {
            config.addMapper(mapper);
        }
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var mappingTarget = new StandardMappingCommandService();
        wire(mappingTarget, "mappingMapper", StandardMappingMapper.class);
        wire(mappingTarget, "elementMapper", StandardElementMapper.class);
        wire(mappingTarget, "columnMapper", MetaColumnMapper.class);
        mappings = proxy(mappingTarget);
        objectTarget = new MetaObjectServiceImpl();
        wire(objectTarget, "dbMapper", MetaDbMapper.class);
        wire(objectTarget, "tableMapper", MetaTableMapper.class);
        wire(objectTarget, "columnMapper", MetaColumnMapper.class);
        wire(objectTarget, "changeMapper", MetaChangeMapper.class);
        wire(objectTarget, "mappingMapper", StandardMappingMapper.class);
        wire(objectTarget, "standardElementMapper", StandardElementMapper.class);
        ReflectionTestUtils.setField(objectTarget, "mappingCommandService", mappings);
        ReflectionTestUtils.setField(objectTarget, "transactionTemplate", new TransactionTemplate(transactionManager));
        wire(objectTarget, "snapshotMapper", MetaTableSnapshotMapper.class);
        wire(objectTarget, "tagMapper", MetaTagMapper.class);
        wire(objectTarget, "tagRelMapper", MetaTagRelMapper.class);
        objects = proxy(objectTarget);
        var tagTarget = new MetaTagServiceImpl();
        wire(tagTarget, "tagMapper", MetaTagMapper.class);
        wire(tagTarget, "tagRelMapper", MetaTagRelMapper.class);
        wire(tagTarget, "dbMapper", MetaDbMapper.class);
        wire(tagTarget, "tableMapper", MetaTableMapper.class);
        wire(tagTarget, "columnMapper", MetaColumnMapper.class);
        wire(tagTarget, "changeMapper", MetaChangeMapper.class);
        tags = proxy(tagTarget);
        jdbc.update("INSERT INTO dh_governance_meta_db(id,resource_urn,datasource_id,db_name,schema_name,db_type) "
                + "VALUES (1,'urn:test:db',1,'test','test','POSTGRESQL')");
        jdbc.update("INSERT INTO dh_governance_meta_table(id,resource_urn,db_id,datasource_id,table_name,source_comment) "
                + "VALUES (10,'urn:test:table',1,1,'customer','源表注释')");
        jdbc.update("INSERT INTO dh_governance_meta_column(id,resource_urn,table_id,db_id,datasource_id,column_name,source_comment) "
                + "VALUES (100,'urn:test:column',10,1,1,'customer_name','源字段注释')");
        jdbc.update("INSERT INTO dh_governance_meta_tag(id,name) VALUES (1,'隐私'),(2,'核心')");
        TenantContextHolder.clear();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (beanFactoryInstalled) {
            ReflectionTestUtils.setField(cn.hutool.extra.spring.SpringUtil.class, "beanFactory", previousBeanFactory);
        }
        if (control != null && schema != null) {
            control.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void sameFieldRevisionAllowsExactlyOneConcurrentWriter() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            int revision = jdbc.queryForObject("SELECT revision FROM dh_governance_meta_column WHERE id=100", Integer.class);
            CountDownLatch ready = new CountDownLatch(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = workers.submit(() -> updateColumn(ready, revision, "甲" + revision));
                Future<Boolean> second = workers.submit(() -> updateColumn(ready, revision, "乙" + revision));
                assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            }
            assertEquals(revision + 1, jdbc.queryForObject(
                    "SELECT revision FROM dh_governance_meta_column WHERE id=100", Integer.class));
        }
        assertEquals("源字段注释", jdbc.queryForObject(
                "SELECT source_comment FROM dh_governance_meta_column WHERE id=100", String.class));
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_change "
                + "WHERE property='CUSTOM_COMMENT'", Integer.class));
    }

    @Test
    void tagSnapshotPreventsStaleClearAndSerializesCompetingSets() throws Exception {
        assertEquals(0, tags.getBindingSnapshot(2, 10L).getRevision());
        CountDownLatch ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(() -> bind(ready, 1L));
            Future<Boolean> second = workers.submit(() -> bind(ready, 2L));
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        var current = tags.getBindingSnapshot(2, 10L);
        assertEquals(1, current.getRevision());
        assertEquals(1, current.getTagIds().size());
        var staleClear = tagRequest(0, List.of());
        assertEquals(META_TAG_BINDING_CONCURRENT_CHANGED.getCode(),
                assertThrows(ServiceException.class, () -> tags.bindTags(staleClear)).getCode());
        assertEquals(current.getTagIds(), tags.getBindingSnapshot(2, 10L).getTagIds());
        tags.bindTags(tagRequest(current.getRevision(), List.of()));
        assertTrue(tags.getBindingSnapshot(2, 10L).getTagIds().isEmpty());
        assertEquals(2, tags.getBindingSnapshot(2, 10L).getRevision());
    }

    @Test
    void tableRevisionRejectsStaleFormWithoutOverwritingSourceOrPublishing() {
        var request = new MetaTableUpdateBizReqVO();
        request.setId(10L);
        request.setBizRevision(0);
        request.setCustomComment("平台维护");
        objects.updateTableBiz(request);
        assertEquals(META_BIZ_CONCURRENT_CHANGED.getCode(),
                assertThrows(ServiceException.class, () -> objects.updateTableBiz(request)).getCode());
        assertEquals("源表注释", jdbc.queryForObject(
                "SELECT source_comment FROM dh_governance_meta_table WHERE id=10", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT publish_status FROM dh_governance_meta_table WHERE id=10", Integer.class));
    }

    @Test
    void temporaryFlagIsIndependentAuditedAndProtectedFromStaleCollectorWrites() {
        jdbc.update("UPDATE dh_governance_meta_table SET engine='OLAP',update_time='2000-01-01' WHERE id=10");
        var mapper = session.getMapper(MetaTableMapper.class);
        MetaTableDO stale = mapper.selectById(10L);
        var request = new MetaTableUpdateBizReqVO();
        request.setId(10L); request.setBizRevision(0); request.setTemporaryFlag(1); request.setStorageType("行式存储");
        objects.updateTableBiz(request);
        assertEquals(1, mapper.selectById(10L).getTemporaryFlag());
        assertEquals(0, mapper.selectById(10L).getSensitiveFlag());
        assertEquals("行式存储", mapper.selectById(10L).getStorageType());
        assertEquals("OLAP", mapper.selectById(10L).getEngine());
        assertTrue(mapper.selectById(10L).getUpdateTime().isAfter(java.time.LocalDateTime.of(2000, 1, 1, 0, 0)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_change "
                + "WHERE property='TEMPORARY_FLAG' AND before_value='0' AND after_value='1'", Integer.class));
        stale.setSourceComment("源结构刷新");
        mapper.updateById(stale);
        assertEquals(1, mapper.selectById(10L).getTemporaryFlag());
        assertEquals("行式存储", mapper.selectById(10L).getStorageType());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE dh_governance_meta_table SET temporary_flag=2 WHERE id=10"));
    }

    @Test
    void auditAndSafeConnectionFieldsFreezeWithoutRemoteCallsWhileHoldingObjectLocks() {
        jdbc.update("UPDATE dh_governance_meta_table SET creator='11',updater='12',temporary_flag=1,storage_type='行式存储' WHERE id=10");
        jdbc.update("UPDATE dh_governance_meta_column SET creator='11',updater='12' WHERE id=100");
        var users = mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class);
        var creator = new cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO();
        creator.setId(11L); creator.setNickname("创建人员"); creator.setMobile("测试联系方式甲");
        var updater = new cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO();
        updater.setId(12L); updater.setNickname("维护人员"); updater.setMobile("测试联系方式乙");
        when(users.getUserList(anyCollection())).thenAnswer(call -> {
            assertNoObjectWriteLocks();
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(List.of(creator, updater));
        });
        var sources = mock(cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi.class);
        var options = new cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO();
        var connection = new cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO.ConnectionOption();
        connection.setId(1L); connection.setName("测试连接"); connection.setHost("example.invalid");
        connection.setPort("5432"); connection.setUsername("metadata_reader");
        options.setConnections(List.of(connection));
        when(sources.options(nullable(Long.class))).thenAnswer(call -> {
            assertNoObjectWriteLocks();
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(options);
        });
        ReflectionTestUtils.setField(objectTarget, "adminUserApi", users);
        ReflectionTestUtils.setField(objectTarget, "metadataCollectionApi", sources);
        var detail = objects.getTableDetail(10L);
        assertEquals("创建人员", detail.getCreatorName());
        assertEquals("维护人员", detail.getUpdaterName());
        assertEquals("example.invalid", detail.getHost());
        assertEquals("维护人员", objects.getColumnDetail(100L).getUpdaterName());
        var snapshot = engine().ensureCurrentSnapshot(session.getMapper(MetaTableMapper.class).selectById(10L));
        var request = new MetaTableFinalizeReqVO();
        request.setId(10L); request.setSnapshotId(snapshot.getId()); request.setStateRevision(0);
        objects.finalizeTable(request);
        reset(users, sources);
        jdbc.update("UPDATE dh_governance_meta_table SET creator='99',temporary_flag=0 WHERE id=10");
        jdbc.update("UPDATE dh_governance_meta_column SET updater='99' WHERE id=100");
        var frozen = objects.getFinalizedTableDetail(10L);
        assertEquals(1, frozen.getTable().getTemporaryFlag());
        assertEquals("行式存储", frozen.getTable().getStorageType());
        assertEquals("创建人员", frozen.getTable().getCreatorName());
        assertEquals("维护人员", frozen.getColumns().getFirst().getUpdaterName());
        assertEquals("example.invalid", frozen.getTable().getHost());
        assertNotNull(frozen.getTable().getCreateTime());
        assertNotNull(frozen.getColumns().getFirst().getUpdateTime());
        assertFalse(JsonUtils.toJsonString(frozen).contains("password"));
        verifyNoInteractions(users, sources);
    }

    private void assertNoObjectWriteLocks() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_locks "
                + "WHERE pid=pg_backend_pid() AND locktype='relation' "
                + "AND mode IN ('RowShareLock','RowExclusiveLock') "
                + "AND relation IN ('dh_governance_meta_db'::regclass,'dh_governance_meta_table'::regclass,"
                + "'dh_governance_meta_column'::regclass)", Integer.class));
    }

    @Test
    void trustedImportNamesRemainSeparateFromPlatformIdentityAndFreezeAsDisplay() {
        String imported = """
                {"source":"qdata","bundleId":"qdata-metadata-20261022","sourceId":1,
                 "datasourceName":"导入展示连接","dbType":"Doris","creatorName":"源端创建人员",
                 "updaterName":"源端维护人员","businessLeaderName":"源端业务人员"}
                """;
        jdbc.update("UPDATE dh_governance_meta_table SET display_snapshot=?::jsonb,creator='qdata-import',"
                + "updater='qdata-import',business_leader_user_id=NULL WHERE id=10", imported);
        jdbc.update("UPDATE dh_governance_meta_column SET display_snapshot=?::jsonb,creator='qdata-import',"
                + "updater='qdata-import' WHERE id=100", imported);
        var view = objects.getTableDetail(10L);
        assertEquals("源端创建人员", view.getCreatorName());
        assertEquals("源端业务人员", view.getBusinessLeaderName());
        assertNull(view.getBusinessLeaderUserId());
        assertEquals("导入展示连接", view.getDatasourceName());
        assertEquals("源端维护人员", objects.getColumnDetail(100L).getUpdaterName());
        var snapshot = engine().ensureCurrentSnapshot(session.getMapper(MetaTableMapper.class).selectById(10L));
        var request = new MetaTableFinalizeReqVO();
        request.setId(10L); request.setSnapshotId(snapshot.getId()); request.setStateRevision(0);
        objects.finalizeTable(request);
        jdbc.update("UPDATE dh_governance_meta_table SET display_snapshot='{}'::jsonb WHERE id=10");
        var frozen = objects.getFinalizedTableDetail(10L);
        assertEquals("源端业务人员", frozen.getTable().getBusinessLeaderName());
        assertEquals("源端创建人员", frozen.getColumns().getFirst().getCreatorName());
        assertFalse(JsonUtils.toJsonString(frozen).contains("displaySnapshot"));
    }

    @Test
    void auditFailureRollsBackBusinessAndTagRevisionTogether() {
        jdbc.execute("CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'test audit unavailable'; END $$");
        jdbc.execute("CREATE TRIGGER reject_change BEFORE INSERT ON dh_governance_meta_change "
                + "FOR EACH ROW EXECUTE FUNCTION reject_change()");
        var businessFailure = assertThrows(RuntimeException.class,
                () -> objects.updateColumnBiz(columnRequest(1, "不可提交")));
        assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(businessFailure)
                .getMessage().contains("test audit unavailable"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT revision FROM dh_governance_meta_column WHERE id=100", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT custom_comment FROM dh_governance_meta_column WHERE id=100", String.class));
        var tagFailure = assertThrows(RuntimeException.class, () -> tags.bindTags(tagRequest(0, List.of(1L))));
        assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(tagFailure)
                .getMessage().contains("test audit unavailable"));
        assertEquals(0, tags.getBindingSnapshot(2, 10L).getRevision());
        assertTrue(tags.getBindingSnapshot(2, 10L).getTagIds().isEmpty());
    }

    @Test
    void confirmedStandardDisplaysCurrentContentAndInvalidatesOpenFieldForm() {
        seedStandard();
        mappings.confirm("METADATA_COLUMN", "urn:test:column", 7, 100L, "MANUAL", "测试落标", "customer_name");
        var detail = objects.getColumnDetail(100L);
        assertEquals(2, detail.getRevision());
        assertEquals(7, detail.getStandardElementId());
        assertEquals("当前名称", detail.getStandardElementName());
        assertEquals("当前名称", objects.getColumnList(10L).getFirst().getStandardElementName());
        assertEquals(META_BIZ_CONCURRENT_CHANGED.getCode(), assertThrows(ServiceException.class,
                () -> objects.updateColumnBiz(columnRequest(1, "旧表单"))).getCode());
        jdbc.update("UPDATE dh_governance_standard_element SET name='更新名称',lifecycle_status='DEPRECATED',status=1 WHERE id=7");
        var commentOnly = columnRequest(2, "保留历史落标，仅改注释");
        commentOnly.setStandardElementId(7);
        objects.updateColumnBiz(commentOnly);
        assertEquals(detail.getStandardMappingId(), objects.getColumnDetail(100L).getStandardMappingId());
        assertEquals("更新名称", objects.getColumnDetail(100L).getStandardElementName());
        mappings.revoke(detail.getStandardMappingId(), "撤销");
        assertEquals(4, objects.getColumnDetail(100L).getRevision());
        assertNull(objects.getColumnDetail(100L).getStandardMappingId());
    }

    @Test
    void nullTagListAndDeletedOwnerCannotEraseBindings() {
        tags.bindTags(tagRequest(0, List.of(1L)));
        var absentList = tagRequest(1, null);
        assertThrows(ServiceException.class, () -> tags.bindTags(absentList));
        jdbc.update("UPDATE dh_governance_meta_table SET object_status=3 WHERE id=10");
        assertEquals(META_OBJECT_NOT_EDITABLE.getCode(),
                assertThrows(ServiceException.class, () -> tags.bindTags(tagRequest(1, List.of()))).getCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_tag_rel WHERE deleted=0", Integer.class));
    }

    @Test
    void staleFullEntityWriteCannotResetIndependentTagOrBusinessRevision() {
        MetaTableMapper mapper = session.getMapper(MetaTableMapper.class);
        MetaTableDO stale = mapper.selectById(10L);
        var request = new MetaTableUpdateBizReqVO();
        request.setId(10L); request.setBizRevision(0); request.setCustomComment("平台更新");
        objects.updateTableBiz(request);
        tags.bindTags(tagRequest(0, List.of(1L)));
        // 通用实体更新不允许提交维护凭据，采集流程另外在事务内锁对象后回读业务属性。
        stale.setCustomComment(null);
        stale.setLastInstanceId(123L);
        mapper.updateById(stale);
        assertEquals(1, mapper.selectById(10L).getTagRevision());
        assertEquals(1, mapper.selectById(10L).getBizRevision());
    }

    @Test
    void collectionWaitsForBusinessEditAndPreservesPublishedPointer() throws Exception {
        jdbc.update("UPDATE dh_governance_meta_table SET publish_status=0,published_version='v1.0.0',"
                + "published_snapshot_id=900 WHERE id=10");
        var engine = new MetaDiffEngine();
        wire(engine, "tableMapper", MetaTableMapper.class);
        wire(engine, "dbMapper", MetaDbMapper.class);
        wire(engine, "snapshotMapper", MetaTableSnapshotMapper.class);
        wire(engine, "changeMapper", MetaChangeMapper.class);
        ReflectionTestUtils.setField(engine, "env", "test");
        ReflectionTestUtils.setField(engine, "transactionTemplate", new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(engine, "mappingCommandService", mappings);
        var actualColumns = session.getMapper(MetaColumnMapper.class);
        var actualDb = session.getMapper(MetaDbMapper.class);
        var observedDb = spy(actualDb);
        var businessLocked = new CountDownLatch(1);
        var collectorReachedDb = new CountDownLatch(1);
        doAnswer(call -> {
            collectorReachedDb.countDown();
            return actualDb.selectByIdForUpdate(call.getArgument(0));
        }).when(observedDb).selectByIdForUpdate(1L);
        ReflectionTestUtils.setField(engine, "dbMapper", observedDb);
        ReflectionTestUtils.setField(engine, "columnMapper", actualColumns);
        var context = new MetaCollectContext();
        context.setInstanceId(55L);
        context.setDatasourceId(1L);
        context.setLogger(mock(MetaCollectLogger.class));
        var source = MetaCollectedModel.CollectedTable.builder()
                .tableName("customer").tableType("TABLE").remarks("新的源表注释").primaryKeys(List.of())
                .columns(List.of(MetaCollectedModel.CollectedColumn.builder()
                        .columnName("customer_name").remarks("新的源字段注释").dataType("varchar")
                        .ordinalPosition(1).nullableFlag(1).pkFlag(0).autoIncrementFlag(0).build())).build();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> edit = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                actualDb.selectByIdForUpdate(1L);
                businessLocked.countDown();
                awaitLatch(collectorReachedDb);
                objects.updateColumnBiz(columnRequest(1, "并发平台维护"));
            }));
            assertTrue(businessLocked.await(10, TimeUnit.SECONDS));
            Future<?> collect = workers.submit(() -> engine.processTable(context,
                    session.getMapper(MetaDbMapper.class).selectById(1L), source));
            edit.get(15, TimeUnit.SECONDS);
            collect.get(15, TimeUnit.SECONDS);
        }
        assertEquals("并发平台维护", actualColumns.selectById(100L).getCustomComment());
        assertEquals("新的源字段注释", actualColumns.selectById(100L).getSourceComment());
        assertEquals(3, actualColumns.selectById(100L).getRevision());
        assertEquals(900L, session.getMapper(MetaTableMapper.class).selectById(10L).getPublishedSnapshotId());
        assertEquals("v1.0.0", session.getMapper(MetaTableMapper.class).selectById(10L).getPublishedVersion());
        assertEquals("100", jdbc.queryForObject(
                "SELECT snapshot->'columns'->0->>'id' FROM dh_governance_meta_table_snapshot", String.class));
        assertEquals("urn:test:column", jdbc.queryForObject(
                "SELECT snapshot->'columns'->0->>'resourceUrn' FROM dh_governance_meta_table_snapshot", String.class));
    }

    @Test
    void deletedColumnReappearsWithFreshIdentityAndNeverInheritsFrozenGovernance() {
        seedStandard();
        Long oldMapping = mappings.confirm("METADATA_COLUMN", "urn:test:column", 7, 100L, "MANUAL", "测试", "customer_name");
        var business = columnRequest(2, "原字段的平台注释");
        business.setStandardElementId(7); business.setBusinessName("原字段业务名称"); business.setSafetyLevelId(5L);
        objects.updateColumnBiz(business);
        var tag = tagRequest(0, List.of(1L));
        tag.setObjectType(3); tag.setObjectId(100L); tags.bindTags(tag);
        jdbc.update("UPDATE dh_governance_meta_table SET business_leader_user_id=1 WHERE id=10");
        var baseline = engine().ensureCurrentSnapshot(session.getMapper(MetaTableMapper.class).selectById(10L));
        var finalizeRequest = new MetaTableFinalizeReqVO();
        finalizeRequest.setId(10L); finalizeRequest.setSnapshotId(baseline.getId()); finalizeRequest.setStateRevision(0);
        objects.finalizeTable(finalizeRequest);
        objects.publishTable(10L, 1);
        var tableMapper = session.getMapper(MetaTableMapper.class);
        Long frozen = tableMapper.selectById(10L).getPublishedSnapshotId();
        String frozenBody = jdbc.queryForObject("SELECT snapshot::text FROM dh_governance_meta_table_snapshot WHERE id=?",
                String.class, frozen);
        var engine = engine();
        var db = session.getMapper(MetaDbMapper.class).selectById(1L);
        engine.processTable(context(), db, collected(false));
        assertEquals("REVOKED", session.getMapper(StandardMappingMapper.class).selectById(oldMapping).getMappingStatus());
        engine.processTable(context(), db, collected(true));
        var fresh = session.getMapper(MetaColumnMapper.class).selectByTableIdAndName(10L, "customer_name");
        assertNotEquals(100L, fresh.getId());
        assertNotEquals("urn:test:column", fresh.getResourceUrn());
        assertNull(fresh.getStandardElementId());
        assertNull(fresh.getCustomComment());
        assertNull(fresh.getBusinessName());
        assertNull(fresh.getSafetyLevelId());
        assertTrue(tags.getBindingSnapshot(3, fresh.getId()).getTagIds().isEmpty());
        assertEquals(3, session.getMapper(MetaColumnMapper.class).selectById(100L).getObjectStatus());
        assertThrows(ServiceException.class, () -> objects.getColumnDetail(100L));
        assertThrows(ServiceException.class, () -> objects.updateColumnBiz(columnRequest(3, "不能修改墓碑")));
        assertTrue(session.getMapper(StandardMappingMapper.class).selectConfirmedMetadataListByTableId(10L).isEmpty());
        var qualityRules = mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper.class);
        var compiler = mock(cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService.class);
        var bindings = proxy(new cn.iocoder.yudao.module.governance.service.quality.StandardQualityRuleBindingService(
                session.getMapper(StandardMappingMapper.class), session.getMapper(MetaColumnMapper.class),
                tableMapper, session.getMapper(MetaDbMapper.class), qualityRules,
                session.getMapper(StandardElementMapper.class),
                new cn.iocoder.yudao.module.governance.service.quality.StandardQualityRuleMaterializer(qualityRules), compiler));
        assertTrue(bindings.resolve(cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO.builder().build(),
                List.of(cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO.builder()
                        .id(1L).tableId(10L).datasourceConnId(1L).tableName("customer").build()), List.of()).isEmpty());
        verifyNoInteractions(qualityRules, compiler);
        assertEquals(frozen, tableMapper.selectById(10L).getPublishedSnapshotId());
        assertEquals(frozenBody, jdbc.queryForObject(
                "SELECT snapshot::text FROM dh_governance_meta_table_snapshot WHERE id=?", String.class, frozen));
        assertNotNull(mappings.confirm("METADATA_COLUMN", fresh.getResourceUrn(), 7, fresh.getId(),
                "MANUAL", "新身份人工重新落标", fresh.getColumnName()));
    }

    @Test
    void confirmedDeletedTableAndDatabaseNeverReuseOldIdentity() {
        var engine = engine();
        var dbMapper = session.getMapper(MetaDbMapper.class);
        var tableMapper = session.getMapper(MetaTableMapper.class);
        jdbc.update("UPDATE dh_governance_meta_table SET object_status=2,business_name='旧表业务名' WHERE id=10");
        objects.confirmDeleteTable(10L);
        engine.processTable(context(), dbMapper.selectById(1L), collected(true));
        var freshTable = tableMapper.selectByDbIdAndName(1L, "customer");
        assertNotEquals(10L, freshTable.getId());
        assertNull(freshTable.getBusinessName());
        assertNull(freshTable.getPublishedSnapshotId());
        assertEquals(3, tableMapper.selectById(10L).getObjectStatus());
        var freshColumn = session.getMapper(MetaColumnMapper.class).selectByTableIdAndName(freshTable.getId(), "customer_name");
        assertNotEquals(100L, freshColumn.getId());
        assertFalse(jdbc.queryForObject("SELECT snapshot::text LIKE '%pending:%' FROM dh_governance_meta_table_snapshot "
                + "WHERE table_id=?", Boolean.class, freshTable.getId()));
        jdbc.update("UPDATE dh_governance_meta_db SET object_status=2 WHERE id=1");
        objects.confirmDeleteDb(1L);
        var freshDb = engine.upsertDb(context(), "test");
        assertNotEquals(1L, freshDb.getId());
        assertNotEquals("urn:test:db", freshDb.getResourceUrn());
        engine.processTable(context(), freshDb, collected(true));
        assertNotNull(tableMapper.selectByDbIdAndName(freshDb.getId(), "customer"));
        assertEquals(3, dbMapper.selectById(1L).getObjectStatus());
        assertEquals(3, tableMapper.selectById(freshTable.getId()).getObjectStatus());
    }

    @Test
    void pendingColumnRecoveryKeepsItsOriginalIdentityAndMapping() {
        seedStandard();
        Long mapping = mappings.confirm("METADATA_COLUMN", "urn:test:column", 7, 100L, "MANUAL", "测试", "customer_name");
        jdbc.update("UPDATE dh_governance_meta_column SET object_status=2,custom_comment='保留治理属性' WHERE id=100");
        engine().processTable(context(), session.getMapper(MetaDbMapper.class).selectById(1L), collected(true));
        assertEquals(100L, session.getMapper(MetaColumnMapper.class).selectByTableIdAndName(10L, "customer_name").getId());
        assertEquals("保留治理属性", objects.getColumnDetail(100L).getCustomComment());
        assertEquals(mapping, objects.getColumnDetail(100L).getStandardMappingId());
    }

    @Test
    void pendingTableRecoveryWinsBeforeStaleConfirmationAndKeepsIdentity() throws Exception {
        jdbc.update("UPDATE dh_governance_meta_table SET object_status=2 WHERE id=10");
        raceRecoveryAgainstConfirmation(false);
        assertEquals(1, session.getMapper(MetaTableMapper.class).selectById(10L).getObjectStatus());
        assertEquals(100L, session.getMapper(MetaColumnMapper.class).selectByTableIdAndName(10L, "customer_name").getId());
    }

    @Test
    void pendingDatabaseRecoveryWinsBeforeStaleConfirmationAndKeepsIdentity() throws Exception {
        jdbc.update("UPDATE dh_governance_meta_db SET object_status=2 WHERE id=1");
        raceRecoveryAgainstConfirmation(true);
        assertEquals(1, session.getMapper(MetaDbMapper.class).selectById(1L).getObjectStatus());
        assertEquals(1, session.getMapper(MetaTableMapper.class).selectById(10L).getObjectStatus());
    }

    private void raceRecoveryAgainstConfirmation(boolean database) throws Exception {
        var actualDb = session.getMapper(MetaDbMapper.class);
        var observedDb = spy(actualDb);
        var restoringLocked = new CountDownLatch(1);
        var confirmingReachedLock = new CountDownLatch(1);
        doAnswer(call -> {
            confirmingReachedLock.countDown();
            return actualDb.selectByIdForUpdate(call.getArgument(0));
        }).when(observedDb).selectByIdForUpdate(1L);
        ReflectionTestUtils.setField(objectTarget, "dbMapper", observedDb);
        var engine = engine();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> restoring = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                var db = actualDb.selectByIdForUpdate(1L);
                restoringLocked.countDown();
                try {
                    assertTrue(confirmingReachedLock.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
                if (database) {
                    assertEquals(1L, engine.upsertDb(context(), "test").getId());
                } else {
                    engine.processTable(context(), db, collected(true));
                }
            }));
            assertTrue(restoringLocked.await(10, TimeUnit.SECONDS));
            Future<?> confirming = workers.submit(() -> {
                ServiceException conflict = assertThrows(ServiceException.class, () -> {
                    if (database) objects.confirmDeleteDb(1L); else objects.confirmDeleteTable(10L);
                });
                assertEquals(database ? META_DB_NOT_PENDING_DELETE.getCode() : META_TABLE_NOT_PENDING_DELETE.getCode(),
                        conflict.getCode());
            });
            restoring.get(15, TimeUnit.SECONDS); confirming.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void finalizationAndPublicationAreIndependentAndFrozenGovernanceDoesNotDrift() {
        seedStandard();
        mappings.confirm("METADATA_COLUMN", "urn:test:column", 7, 100L, "MANUAL", "测试", "customer_name");
        var business = columnRequest(2, "定版字段业务注释");
        business.setBusinessName("客户名称"); business.setStandardElementId(7);
        objects.updateColumnBiz(business);
        tags.bindTags(tagRequest(0, List.of(1L)));
        jdbc.update("UPDATE dh_governance_meta_table SET business_leader_user_id=1,business_name='定版表名' WHERE id=10");
        var tableMapper = session.getMapper(MetaTableMapper.class);
        var snapshot = engine().ensureCurrentSnapshot(tableMapper.selectById(10L));
        var request = new MetaTableFinalizeReqVO();
        request.setId(10L); request.setSnapshotId(snapshot.getId()); request.setStateRevision(0);
        objects.finalizeTable(request);
        assertEquals(0, tableMapper.selectById(10L).getPublishStatus());
        assertEquals(1, tableMapper.selectById(10L).getStateRevision());
        assertTrue(objects.getFinalizedTableDetail(10L).getTable().getReadOnly());
        assertEquals("定版字段业务注释", objects.getFinalizedTableDetail(10L).getColumns().getFirst().getCustomComment());
        assertEquals("发布名称", objects.getFinalizedTableDetail(10L).getColumns().getFirst().getStandardElementName());
        assertEquals("隐私", objects.getFinalizedTableDetail(10L).getTable().getTags().getFirst().getName());
        var later = columnRequest(3, "之后修改");
        later.setStandardElementId(7); objects.updateColumnBiz(later);
        tags.bindTags(tagRequest(1, List.of(2L)));
        jdbc.update("UPDATE dh_governance_meta_table SET business_name='之后表名' WHERE id=10");
        assertEquals("定版表名", objects.getFinalizedTableDetail(10L).getTable().getBusinessName());
        assertEquals("定版字段业务注释", objects.getFinalizedTableDetail(10L).getColumns().getFirst().getCustomComment());
        objects.publishTable(10L, 1);
        assertEquals(snapshot.getId(), tableMapper.selectById(10L).getPublishedSnapshotId());
        assertEquals(META_TABLE_PUBLISHED_READ_ONLY.getCode(), assertThrows(ServiceException.class,
                () -> objects.updateColumnBiz(columnRequest(4, "发布后不能修改"))).getCode());
        objects.unpublishTable(10L, 2);
        assertEquals(snapshot.getId(), tableMapper.selectById(10L).getPublishedSnapshotId());
        objects.publishTable(10L, 3);
        var cancel = new MetaTableStateReqVO(); cancel.setId(10L); cancel.setStateRevision(4);
        objects.cancelFinalizeTable(cancel);
        assertEquals(1, tableMapper.selectById(10L).getPublishStatus());
        assertNull(tableMapper.selectById(10L).getPublishedSnapshotId());
        assertEquals(META_TABLE_NOT_FINALIZED.getCode(), assertThrows(ServiceException.class,
                () -> objects.getFinalizedTableDetail(10L)).getCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_change WHERE property='FINALIZED_VERSION'", Integer.class));
    }

    @Test
    void sameRevisionAllowsOnlyOneConcurrentStateTransition() throws Exception {
        jdbc.update("UPDATE dh_governance_meta_table SET business_leader_user_id=1 WHERE id=10");
        CountDownLatch ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> publish = () -> {
                ready.countDown(); assertTrue(ready.await(10, TimeUnit.SECONDS));
                try {
                    objects.publishTable(10L, 0); return true;
                } catch (ServiceException conflict) {
                    assertEquals(META_STATE_CONCURRENT_CHANGED.getCode(), conflict.getCode()); return false;
                }
            };
            var first = workers.submit(publish); var second = workers.submit(publish);
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT state_revision FROM dh_governance_meta_table WHERE id=10", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_change WHERE property='PUBLISH_STATUS'", Integer.class));
    }

    @Test
    void publicationPreventsConcurrentColumnMaintenance() throws Exception {
        assertPublicationPreventsMaintenance(true);
    }

    @Test
    void publicationPreventsConcurrentTableMaintenance() throws Exception {
        assertPublicationPreventsMaintenance(false);
    }

    private void assertPublicationPreventsMaintenance(boolean column) throws Exception {
        jdbc.update("UPDATE dh_governance_meta_table SET business_leader_user_id=1 WHERE id=10");
        var actualDb = session.getMapper(MetaDbMapper.class);
        var observedDb = spy(actualDb);
        var publisherLocked = new CountDownLatch(1);
        var editorReachedDb = new CountDownLatch(1);
        doAnswer(call -> {
            editorReachedDb.countDown();
            return actualDb.selectByIdForUpdate(call.getArgument(0));
        }).when(observedDb).selectByIdForUpdate(1L);
        ReflectionTestUtils.setField(objectTarget, "dbMapper", observedDb);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> publish = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                actualDb.selectByIdForUpdate(1L);
                publisherLocked.countDown();
                awaitLatch(editorReachedDb);
                objects.publishTable(10L, 0);
            }));
            assertTrue(publisherLocked.await(10, TimeUnit.SECONDS));
            Future<?> edit = workers.submit(() -> {
                ServiceException conflict = assertThrows(ServiceException.class, () -> {
                    if (column) {
                        objects.updateColumnBiz(columnRequest(1, "发布后不应提交"));
                    } else {
                        var request = new MetaTableUpdateBizReqVO();
                        request.setId(10L); request.setBizRevision(0); request.setCustomComment("发布后不应提交");
                        objects.updateTableBiz(request);
                    }
                });
                assertEquals(META_TABLE_PUBLISHED_READ_ONLY.getCode(), conflict.getCode());
            });
            publish.get(15, TimeUnit.SECONDS);
            edit.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT publish_status FROM dh_governance_meta_table WHERE id=10", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT biz_revision FROM dh_governance_meta_table WHERE id=10", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT revision FROM dh_governance_meta_column WHERE id=100", Integer.class));
        assertNull(jdbc.queryForObject("SELECT custom_comment FROM dh_governance_meta_column WHERE id=100", String.class));
        assertNull(jdbc.queryForObject("SELECT custom_comment FROM dh_governance_meta_table WHERE id=10", String.class));
    }

    @Test
    void finalizationWaitsForConcurrentColumnMaintenanceAndFreezesCommittedResult() throws Exception {
        var snapshot = engine().ensureCurrentSnapshot(session.getMapper(MetaTableMapper.class).selectById(10L));
        var request = new MetaTableFinalizeReqVO();
        request.setId(10L); request.setSnapshotId(snapshot.getId()); request.setStateRevision(0);
        var actualDb = session.getMapper(MetaDbMapper.class);
        var observedDb = spy(actualDb);
        var editorLocked = new CountDownLatch(1);
        var finalizerReachedDb = new CountDownLatch(1);
        doAnswer(call -> {
            finalizerReachedDb.countDown();
            return actualDb.selectByIdForUpdate(call.getArgument(0));
        }).when(observedDb).selectByIdForUpdate(1L);
        ReflectionTestUtils.setField(objectTarget, "dbMapper", observedDb);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> edit = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                actualDb.selectByIdForUpdate(1L);
                editorLocked.countDown();
                awaitLatch(finalizerReachedDb);
                objects.updateColumnBiz(columnRequest(1, "定版等待完成的字段维护"));
            }));
            assertTrue(editorLocked.await(10, TimeUnit.SECONDS));
            Future<?> finalize = workers.submit(() -> objects.finalizeTable(request));
            edit.get(15, TimeUnit.SECONDS);
            finalize.get(15, TimeUnit.SECONDS);
        }
        assertEquals("定版等待完成的字段维护", objects.getFinalizedTableDetail(10L).getColumns().getFirst().getCustomComment());
        assertEquals(2, jdbc.queryForObject("SELECT revision FROM dh_governance_meta_column WHERE id=100", Integer.class));
    }

    @Test
    void tableAndColumnRemarkChangesAreAuditedIncludingClear() {
        var table = new MetaTableUpdateBizReqVO();
        table.setId(10L); table.setBizRevision(0); table.setRemark("表业务备注");
        objects.updateTableBiz(table);
        var column = columnRequest(1, null);
        column.setRemark("字段业务备注");
        objects.updateColumnBiz(column);
        objects.updateColumnBiz(columnRequest(2, null));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM dh_governance_meta_change WHERE property='REMARK'", Integer.class));
        assertEquals("表业务备注", jdbc.queryForObject("SELECT after_value FROM dh_governance_meta_change "
                + "WHERE property='REMARK' AND object_type=2", String.class));
        assertEquals("字段业务备注", jdbc.queryForObject("SELECT before_value FROM dh_governance_meta_change "
                + "WHERE property='REMARK' AND object_type=3 AND after_value IS NULL", String.class));
        assertNull(jdbc.queryForObject("SELECT remark FROM dh_governance_meta_column WHERE id=100", String.class));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    void snapshotsMustBelongToRequestedTableAndDeletedObjectsCannotBeRead() {
        var snapshot = engine().ensureCurrentSnapshot(session.getMapper(MetaTableMapper.class).selectById(10L));
        jdbc.update("INSERT INTO dh_governance_meta_table(id,resource_urn,db_id,datasource_id,table_name) VALUES(11,'urn:test:other',1,1,'other')");
        var request = new MetaTableFinalizeReqVO(); request.setId(11L); request.setSnapshotId(snapshot.getId()); request.setStateRevision(0);
        assertEquals(META_SNAPSHOT_TABLE_MISMATCH.getCode(), assertThrows(ServiceException.class,
                () -> objects.finalizeTable(request)).getCode());
        assertEquals(META_SNAPSHOT_TABLE_MISMATCH.getCode(), assertThrows(ServiceException.class,
                () -> objects.compareSnapshots(11L, snapshot.getId(), snapshot.getId())).getCode());
        jdbc.update("UPDATE dh_governance_meta_table SET object_status=3 WHERE id=10");
        assertEquals(META_TABLE_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> objects.getSnapshot(snapshot.getId())).getCode());
    }

    private MetaDiffEngine engine() {
        var engine = new MetaDiffEngine();
        wire(engine, "tableMapper", MetaTableMapper.class); wire(engine, "dbMapper", MetaDbMapper.class);
        wire(engine, "columnMapper", MetaColumnMapper.class); wire(engine, "snapshotMapper", MetaTableSnapshotMapper.class);
        wire(engine, "changeMapper", MetaChangeMapper.class);
        ReflectionTestUtils.setField(engine, "env", "test");
        ReflectionTestUtils.setField(engine, "transactionTemplate", new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(engine, "mappingCommandService", mappings);
        return engine;
    }

    private MetaCollectContext context() {
        var context = new MetaCollectContext();
        context.setInstanceId(55L); context.setDatasourceId(1L); context.setDbType("POSTGRESQL");
        context.setLogger(mock(MetaCollectLogger.class)); return context;
    }

    private MetaCollectedModel.CollectedTable collected(boolean withColumn) {
        return MetaCollectedModel.CollectedTable.builder().tableName("customer").tableType("TABLE")
                .remarks("源表注释").primaryKeys(List.of()).columns(withColumn ? List.of(
                        MetaCollectedModel.CollectedColumn.builder().columnName("customer_name").remarks("源字段注释")
                                .dataType("varchar").ordinalPosition(1).nullableFlag(1).pkFlag(0).autoIncrementFlag(0).build())
                        : List.of()).build();
    }

    private void seedStandard() {
        jdbc.update("INSERT INTO dh_governance_standard_element(id,code,name,eng_name,column_type,lifecycle_status,status) "
                + "VALUES (7,'customerName','当前名称','customer_name','VARCHAR','PUBLISHED',0)");
    }

    private boolean updateColumn(CountDownLatch ready, int revision, String comment) throws Exception {
        ready.countDown();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        try {
            objects.updateColumnBiz(columnRequest(revision, comment));
            return true;
        } catch (ServiceException error) {
            assertEquals(META_BIZ_CONCURRENT_CHANGED.getCode(), error.getCode());
            return false;
        }
    }

    private boolean bind(CountDownLatch ready, Long tag) throws Exception {
        ready.countDown();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        try {
            tags.bindTags(tagRequest(0, List.of(tag)));
            return true;
        } catch (ServiceException error) {
            assertEquals(META_TAG_BINDING_CONCURRENT_CHANGED.getCode(), error.getCode());
            return false;
        }
    }

    private MetaColumnUpdateBizReqVO columnRequest(int revision, String comment) {
        var request = new MetaColumnUpdateBizReqVO();
        request.setId(100L); request.setRevision(revision); request.setCustomComment(comment);
        return request;
    }

    private MetaTagBindReqVO tagRequest(int revision, List<Long> tagIds) {
        var request = new MetaTagBindReqVO();
        request.setObjectType(2); request.setObjectId(10L); request.setRevision(revision); request.setTagIds(tagIds);
        return request;
    }

    private void wire(Object target, String field, Class<?> mapper) {
        ReflectionTestUtils.setField(target, field, session.getMapper(mapper));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
