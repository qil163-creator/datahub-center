package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataFileEntryDTO;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.MetaFilePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.MetaFileUpdateBizReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectQueue;
import cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真 PostgreSQL 验证持久队列、抢占、恢复以及文件元数据的业务修订隔离。 */
@EnabledIfEnvironmentVariable(named="METADATA_WRITE_TEST_JDBC_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-zA-Z0-9_]+")
@Timeout(40)
class MetadataCollectionPostgreSqlTest {
    JdbcTemplate control, jdbc;
    String schema;
    MetaCollectQueue queue;
    MetaCollectTaskMapper tasks;
    MetaCollectInstanceMapper instances;
    MetaFileMapper fileMapper;
    MetaFileService files;
    MetadataCollectionApi provider;
    MetaCollectLogMapper logs;
    TransactionTemplate transactions;
    cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor executor;
    Object previousBeans;

    @BeforeEach void setup() {
        String url=System.getenv("METADATA_WRITE_TEST_JDBC_URL"), user=System.getenv("METADATA_WRITE_TEST_JDBC_USER");
        String password=System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_PASSWORD", "");
        control=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="metadata_queue_"+UUID.randomUUID().toString().replace("-", "");
        control.execute("CREATE SCHEMA "+schema);
        SingleTenantPostgreSqlFixture.copyCurrentTables(control,schema,List.of("dh_governance_meta_collect_task", "dh_governance_meta_collect_instance", "dh_governance_meta_file", "dh_governance_meta_collect_log"));
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password); jdbc=new JdbcTemplate(ds);
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var interceptor=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        config.setEnvironment(new Environment("metadata-queue",new SpringManagedTransactionFactory(),ds));
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        for(var type:List.of(MetaCollectTaskMapper.class,MetaCollectInstanceMapper.class,MetaFileMapper.class,MetaCollectLogMapper.class)) config.addMapper(type);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        tasks=session.getMapper(MetaCollectTaskMapper.class); instances=session.getMapper(MetaCollectInstanceMapper.class); fileMapper=session.getMapper(MetaFileMapper.class);
        logs=session.getMapper(MetaCollectLogMapper.class);
        previousBeans=ReflectionTestUtils.getField(cn.hutool.extra.spring.SpringUtil.class,"beanFactory");
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory(); beans.registerSingleton("dataSource",ds);
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(beans);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds)); transactions=tx;
        queue=new MetaCollectQueue(); ReflectionTestUtils.setField(queue,"tasks",tasks); ReflectionTestUtils.setField(queue,"instances",instances); ReflectionTestUtils.setField(queue,"transactions",tx);
        provider=mock(MetadataCollectionApi.class); files=new MetaFileService();
        var options=new MetadataSourceOptionsDTO(); options.setConnections(List.of()); options.setSystems(List.of());
        when(provider.options(any())).thenReturn(CommonResult.success(options));
        ReflectionTestUtils.setField(files,"mapper",fileMapper); ReflectionTestUtils.setField(files,"sources",provider);
        ReflectionTestUtils.setField(files,"dbs",mock(MetaDbMapper.class));
        ReflectionTestUtils.setField(files,"instances",instances);
        ReflectionTestUtils.setField(files,"users",mock(AdminUserApi.class)); ReflectionTestUtils.setField(files,"transactions",tx);
    }
    @AfterEach void clean() throws Exception {
        if(executor!=null) {
            executor.destroy();
            ((ExecutorService)ReflectionTestUtils.getField(executor,"executor")).awaitTermination(5,TimeUnit.SECONDS);
        }
        ReflectionTestUtils.setField(cn.hutool.extra.spring.SpringUtil.class,"beanFactory",previousBeans);
        if(control!=null && schema!=null) control.execute("DROP SCHEMA "+schema+" CASCADE");
    }
    MetaCollectTaskDO task(String strategy) {
        var row=MetaCollectTaskDO.builder().name("测试采集-"+strategy).datasourceId(7L).datasourceName("隔离连接").scopeType(1)
                .collectType(2).filePath("/a").executeStrategy(strategy).scheduler("XXL_JOB").enabled(1).scheduleEnabled(0).build();
        row.setCreator("9"); tasks.insert(row); return row;
    }
    void terminal(Long id) { jdbc.update("UPDATE dh_governance_meta_collect_instance SET status=2 WHERE id=?",id); }

    @Test void waitIsFifoAndParallelClaimsHaveGlobalBound() {
        var serial=task("SERIAL_WAIT");
        var first=queue.enqueue(serial.getId(),2,9L); var second=queue.enqueue(serial.getId(),2,9L);
        assertEquals(first.getId(),queue.claim().getId()); assertNull(queue.claim()); terminal(first.getId());
        assertEquals(second.getId(),queue.claim().getId()); terminal(second.getId());
        var parallel=task("PARALLEL");
        queue.enqueue(parallel.getId(),2,9L); queue.enqueue(parallel.getId(),2,9L); queue.enqueue(parallel.getId(),2,9L);
        assertNotNull(queue.claim()); assertNotNull(queue.claim()); assertNull(queue.claim());
    }
    @Test void discardAndPriorityKeepRunningButReplaceOnlyWaiting() {
        var discard=task("SERIAL_DISCARD"); var active=queue.enqueue(discard.getId(),2,9L);
        assertEquals(5,queue.enqueue(discard.getId(),2,9L).getStatus()); terminal(active.getId());
        var priority=task("SERIAL_PRIORITY"); var running=queue.enqueue(priority.getId(),2,9L); queue.claim();
        var old=queue.enqueue(priority.getId(),2,9L); var latest=queue.enqueue(priority.getId(),2,9L);
        assertEquals(1,instances.selectById(running.getId()).getStatus()); assertEquals(5,instances.selectById(old.getId()).getStatus());
        assertNull(queue.claim()); terminal(running.getId()); assertEquals(latest.getId(),queue.claim().getId());
    }
    @Test void concurrentClaimDoesNotDuplicateAndExpiredTokensStopRenewing() throws Exception {
        var task=task("PARALLEL"); queue.enqueue(task.getId(),2,9L);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<MetaCollectInstanceDO> action=()->{gate.await();return queue.claim();};
            var one=pool.submit(action); var two=pool.submit(action); gate.countDown();
            var results=Arrays.asList(one.get(),two.get()); assertEquals(1,results.stream().filter(Objects::nonNull).count());
            var original=results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            for(int i=0;i<3;i++) {
                jdbc.update("UPDATE dh_governance_meta_collect_instance SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",original.getId());
                queue.recoverExpired();
                assertEquals(0,instances.renew(original.getId(),original.getExecutionToken()));
                if(i<2) assertNotNull(queue.claim());
            }
            assertEquals(3,instances.selectById(original.getId()).getStatus()); assertNull(queue.claim());
        }
    }
    @Test void scheduleAdvanceAndEnqueueAreAtomicAndIdempotent() {
        var task=task("SERIAL_WAIT"); var expected=LocalDateTime.now().minusMinutes(1).withNano(0); var next=expected.plusMinutes(2);
        jdbc.update("UPDATE dh_governance_meta_collect_task SET schedule_enabled=1,next_run_time=? WHERE id=?",expected,task.getId());
        assertNotNull(queue.enqueue(task.getId(),1,null,expected,next)); assertNull(queue.enqueue(task.getId(),1,null,expected,next));
        assertEquals(1L,instances.selectCount(null)); assertEquals(next,tasks.selectById(task.getId()).getNextRunTime());
    }
    @Test void technicalCollectionPreservesBusinessRevisionAndOnlyMarksItsScope() {
        var task=task("SERIAL_WAIT"); var instance=queue.enqueue(task.getId(),2,9L);
        when(provider.fileManifest(7L,"/a",9L)).thenReturn(CommonResult.success(List.of(entry("/a/test.csv"))));
        var first=files.collect(task,instance,()->{});
        assertEquals(new MetaFileService.CollectionResult(1,1,0,0),first); var row=fileMapper.selectList().getFirst();
        var req=new MetaFileUpdateBizReqVO(); req.setId(row.getId()); req.setBizRevision(0); req.setBusinessName("业务说明"); files.updateBiz(req);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->files.updateBiz(req));
        assertEquals(first,files.collect(task,instance,()->{})); var refreshed=fileMapper.selectById(row.getId());
        assertEquals("业务说明",refreshed.getBusinessName()); assertEquals(1,refreshed.getBizRevision());
        task.setFilePath("/b"); when(provider.fileManifest(7L,"/b",9L)).thenReturn(CommonResult.success(List.of()));
        files.collect(task,instance,()->{}); assertEquals(1,fileMapper.selectById(row.getId()).getObjectStatus());
        task.setFilePath("/a"); when(provider.fileManifest(7L,"/a",9L)).thenReturn(CommonResult.success(List.of()));
        assertEquals(1,files.collect(task,instance,()->{}).missing()); assertEquals(2,fileMapper.selectById(row.getId()).getObjectStatus());
        assertEquals(1,files.collect(task,instance,()->{}).missing());
        assertEquals(1,instances.selectById(instance.getId()).getTableDelete());
        files.delete(List.of(row.getId())); assertNull(fileMapper.selectById(row.getId()));
    }
    @Test void fileFiltersAndTreeMatchNestedAndUnassignedSourcesAndClearUnknownModifiedTime() {
        var task=task("SERIAL_WAIT"); var instance=queue.enqueue(task.getId(),2,9L);
        var manifest=entry("/a/deep/test.csv"); manifest.setModifiedTime(1760000000000L);
        when(provider.fileManifest(7L,"/a",9L)).thenReturn(CommonResult.success(List.of(manifest)));
        files.collect(task,instance,()->{}); var row=fileMapper.selectList().getFirst();
        assertNotNull(row.getSourceModifiedTime());
        manifest.setModifiedTime(0L);
        assertEquals(1,files.collect(task,instance,()->{}).updated());
        assertNull(fileMapper.selectById(row.getId()).getSourceModifiedTime());
        assertEquals(1,files.collect(task,instance,()->{}).updated());
        var connection=files.tree().getFirst().getChildren().getFirst();
        assertEquals("connection",connection.getType()); assertNull(connection.getPath());
        var req=new MetaFilePageReqVO(); req.setDatasourceId(connection.getDatasourceId()); req.setFileType("csv");
        assertEquals(1L,files.page(req).getTotal());
        req.setPath("/"); assertEquals(0L,files.page(req).getTotal());
        req.setPath("/a/deep"); assertEquals(1L,files.page(req).getTotal());
        req.setSourceSystemMissing(true); assertEquals(1L,files.page(req).getTotal());
        task.setSourceSystemId(12L); task.setSourceSystemName("测试来源系统"); files.collect(task,instance,()->{});
        assertEquals(0L,files.page(req).getTotal());
        task.setSourceSystemId(null); task.setSourceSystemName(null); files.collect(task,instance,()->{});
        assertEquals(1L,files.page(req).getTotal());
    }
    @Test void failedOrOutOfScopeManifestCannotModifyExistingMetadata() {
        var task=task("SERIAL_WAIT"); var instance=queue.enqueue(task.getId(),2,9L);
        when(provider.fileManifest(7L,"/a",9L)).thenReturn(CommonResult.success(List.of(entry("/outside/test.csv"))));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->files.collect(task,instance,()->{}));
        assertEquals(0L,fileMapper.selectCount(null));
    }
    @Test void actualWorkerPersistsFileStatsLogsAndFreshInstanceReportsNoChange() throws Exception {
        var task=task("SERIAL_WAIT"); var permissions=startExecutor();
        when(permissions.hasAnyPermissions(eq(9L),any(String.class))).thenReturn(CommonResult.success(true));
        when(provider.fileManifest(7L,"/a",9L)).thenReturn(CommonResult.success(List.of(entry("/a/test.csv"))));
        assertTrue(executor.submit(task.getId(),2));
        var first=awaitTerminal(tasks.selectById(task.getId()).getLastInstanceId());
        assertEquals(2,first.getStatus()); assertEquals(1,first.getFileCount()); assertEquals(1,first.getTableAdd());
        assertEquals(0,first.getTableUpdate()); assertEquals(0,first.getTableDelete());
        assertTrue(logs.selectListByInstanceId(first.getId()).stream().anyMatch(log->log.getContent().contains("文件新增=1")));
        assertTrue(executor.submit(task.getId(),2));
        var second=awaitTerminal(tasks.selectById(task.getId()).getLastInstanceId());
        assertEquals(2,second.getStatus()); assertEquals(1,second.getFileCount()); assertEquals(0,second.getTableAdd());
        assertEquals(0,second.getTableUpdate()); assertEquals(0,second.getTableDelete());
    }
    @Test void revokedActorPermissionFailsWorkerWithoutReadingRemoteSource() throws Exception {
        var task=task("SERIAL_WAIT"); var permissions=startExecutor();
        when(permissions.hasAnyPermissions(eq(9L),any(String.class))).thenReturn(CommonResult.success(false));
        assertTrue(executor.submit(task.getId(),2));
        var result=awaitTerminal(tasks.selectById(task.getId()).getLastInstanceId());
        assertEquals(3,result.getStatus()); verify(provider,never()).fileManifest(anyLong(),anyString(),anyLong());
        assertEquals(0L,fileMapper.selectCount(null));
    }
    @Test void taskCronValidationUsesLockedScheduleStateAndSwitchesStayIndependent() {
        var task=task("SERIAL_WAIT"); jdbc.update("UPDATE dh_governance_meta_collect_task SET cron='0 * * * * *' WHERE id=?",task.getId());
        var service=new MetaCollectTaskServiceImpl(); var source=mock(cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectSources.class);
        ReflectionTestUtils.setField(service,"taskMapper",tasks); ReflectionTestUtils.setField(service,"transactions",transactions);
        ReflectionTestUtils.setField(service,"sources",source);
        ReflectionTestUtils.setField(service,"collectExecutor",mock(cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor.class));
        var req=new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO();
        req.setId(task.getId()); req.setName(task.getName()); req.setDatasourceId(7L); req.setCollectType(2); req.setScopeType(1); req.setFilePath("/a");
        // 模拟校验外部来源时另一请求先开启调度；后续持锁保存必须看到最新状态。
        doAnswer(call->{service.updateScheduleEnabled(task.getId(),1);return null;}).when(source).validateAndEnrich(any(),any());
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.updateTask(req));
        assertEquals("0 * * * * *",tasks.selectById(task.getId()).getCron());
        service.updateEnabled(task.getId(),0); assertEquals(1,tasks.selectById(task.getId()).getScheduleEnabled());
        service.updateScheduleEnabled(task.getId(),0); assertEquals(0,tasks.selectById(task.getId()).getEnabled());
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.updateEnabled(task.getId(),2));
    }
    cn.iocoder.yudao.module.system.api.permission.PermissionApi startExecutor() {
        executor=new cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor();
        var permissions=mock(cn.iocoder.yudao.module.system.api.permission.PermissionApi.class);
        ReflectionTestUtils.setField(executor,"taskMapper",tasks); ReflectionTestUtils.setField(executor,"instanceMapper",instances);
        ReflectionTestUtils.setField(executor,"logMapper",logs); ReflectionTestUtils.setField(executor,"queue",queue);
        ReflectionTestUtils.setField(executor,"permissions",permissions); ReflectionTestUtils.setField(executor,"files",files);
        ReflectionTestUtils.setField(executor,"transactionTemplate",transactions); executor.init(); return permissions;
    }
    MetaCollectInstanceDO awaitTerminal(Long id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            var instance=instances.selectById(id);
            if(instance.getStatus()>=2 && !logs.selectListByInstanceId(id).isEmpty()) return instance;
            Thread.sleep(25);
        }
        fail("采集工作线程未按时完成"); return null;
    }
    MetadataFileEntryDTO entry(String path) { var e=new MetadataFileEntryDTO();e.setPath(path);e.setName("test.csv");e.setSize(2);return e; }
}
