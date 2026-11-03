package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared real platform PostgreSQL mapper + Spring transaction fixture; target writing is never mocked. */
final class EtlBatchPgFixture implements AutoCloseable {
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    final Connection control;
    final String schema = "etl_target_" + UUID.randomUUID().toString().replace("-", "");
    final String targetSchema = schema + "_dwd";
    final AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext();
    final DbSwitchRuntime runtime = DbSwitchRuntime.standalone();
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    final QualityTaskApi qualityApi = mock(QualityTaskApi.class);
    final cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi databaseApi = mock(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class);
    final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    final EtlTargetDatabaseAdapter adapter;
    final EtlTargetBatchService service;
    final EtlRunCoordinator coordinator;
    final EtlRecordMapper records;
    final EtlPipelineMapper pipelines;
    final EtlTargetBatchMapper batches;
    final DataSource platform;

    EtlBatchPgFixture(PostgreSQLContainer<?> postgres) throws Exception {
        control = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        sql("CREATE SCHEMA " + schema); sql("CREATE SCHEMA " + targetSchema); sql("SET search_path TO " + schema);
        sql("CREATE TABLE dh_dataforge_access(id bigint,tenant_id bigint,deleted smallint)");
        sql("CREATE TABLE dh_dataforge_access_task(id bigint,data_access_id bigint,tenant_id bigint,run_status smallint,deleted smallint default 0)");
        sql("CREATE TABLE dh_dataforge_etl_pipeline(environment_id bigint,id bigint primary key,tenant_id bigint,name varchar(50),task_type smallint,cron varchar(200),"
                + "next_run_time timestamp,status smallint,schedule_status smallint,run_status smallint,start_time timestamp,end_time timestamp,"
                + "release_time timestamp,release_user_id bigint,description text,nodes_json jsonb,edges_json jsonb,params_json jsonb,org_id bigint,"
                + "creator varchar(64),updater varchar(64),create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        sql("CREATE TABLE dh_dataforge_etl_record(id bigint primary key,pipeline_id bigint,tenant_id bigint,name varchar(50),run_status smallint,execute_type smallint,"
                + "start_time timestamp,end_time timestamp,log text,config_json jsonb,org_id bigint,creator varchar(64),updater varchar(64),"
                + "create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        sql("CREATE TABLE dh_dataforge_etl_tool(id bigint PRIMARY KEY,parent_id bigint,name text,icon text,type integer,code text,built_in integer,enabled integer,order_no integer,description text,tenant_id bigint,creator text,create_time timestamp,updater text,update_time timestamp,deleted smallint)");
        migrate("0038_dataforge_run-protocol.sql"); migrate("0049_dataforge_run-datasource-capacity.sql");
        migrate("0078_dataforge_etl-governance-evidence.sql"); migrate("0081_dataforge_etl-version-governance-link.sql");
        migrate("0088_dataforge_etl-target-batch.sql"); migrate("0088_dataforge_etl-target-batch.sql");
        migrate("0092_dataforge_etl-lineage-archive.sql"); migrate("0093_dataforge_etl-environment-binding.sql");
        sql("CREATE TABLE " + targetSchema + ".rows(id bigint NOT NULL,amount numeric,observed_at timestamptz,raw bytea,"
                + "_quality_status text NOT NULL,_etl_batch_key text NOT NULL,_etl_row_no bigint NOT NULL,UNIQUE(_etl_batch_key,_etl_row_no))");
        platform = new DriverManagerDataSource(postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema, postgres.getUsername(), postgres.getPassword());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("target-real-pg", new SpringManagedTransactionFactory(), platform));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var interceptors = new MybatisPlusInterceptor();

        config.addInterceptor(interceptors);
        config.addMapper(EtlRecordMapper.class); config.addMapper(EtlPipelineMapper.class);
        config.addMapper(EtlQualityBatchMapper.class); config.addMapper(EtlTargetBatchMapper.class);
        config.addMapper(EtlQualityIssueMapper.class);
        config.addMapper(EtlEnvironmentMapper.class); config.addMapper(EtlEnvironmentBindingMapper.class); config.addMapper(EtlArchiveMapper.class);

        config.addMapper(EtlOperationsMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        app.register(Transactions.class);
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(platform));
        app.registerBean(EtlRecordMapper.class, () -> sessions.getMapper(EtlRecordMapper.class));
        app.registerBean(EtlPipelineMapper.class, () -> sessions.getMapper(EtlPipelineMapper.class));
        app.registerBean(EtlQualityBatchMapper.class, () -> sessions.getMapper(EtlQualityBatchMapper.class));
        app.registerBean(EtlQualityIssueMapper.class, () -> sessions.getMapper(EtlQualityIssueMapper.class));
        app.registerBean(EtlTargetBatchMapper.class, () -> sessions.getMapper(EtlTargetBatchMapper.class));
        app.registerBean(EtlEnvironmentMapper.class, () -> sessions.getMapper(EtlEnvironmentMapper.class));
        app.registerBean(EtlEnvironmentBindingMapper.class, () -> sessions.getMapper(EtlEnvironmentBindingMapper.class));
        app.registerBean(EtlArchiveMapper.class, () -> sessions.getMapper(EtlArchiveMapper.class));

        app.registerBean(EtlOperationsMapper.class, () -> sessions.getMapper(EtlOperationsMapper.class));
        app.registerBean(EtlEnvironmentService.class);
        app.registerBean(RunExecutionProperties.class);
        app.getBeanFactory().registerSingleton("metrics", mock(RunMetrics.class));
        app.getBeanFactory().registerSingleton("versionService", mock(EtlVersionService.class));
        app.getBeanFactory().registerSingleton("replays", mock(EtlReplayMapper.class));
        app.getBeanFactory().registerSingleton("archiveService", mock(EtlArchiveService.class));
        app.getBeanFactory().registerSingleton("runtime", runtime);
        app.getBeanFactory().registerSingleton("connections", connections);
        app.getBeanFactory().registerSingleton("access", access);
        app.getBeanFactory().registerSingleton("queries", queries);
        app.getBeanFactory().registerSingleton("qualityApi", qualityApi);
        app.getBeanFactory().registerSingleton("databaseApi", databaseApi);
        app.registerBean(EtlTargetDatabaseAdapter.class, () -> spy(new EtlTargetDatabaseAdapter()));
        app.registerBean(EtlRunCoordinator.class); app.registerBean(EtlTargetBatchService.class); app.refresh();
        adapter = app.getBean(EtlTargetDatabaseAdapter.class); service = app.getBean(EtlTargetBatchService.class);
        coordinator = app.getBean(EtlRunCoordinator.class); records = app.getBean(EtlRecordMapper.class);
        pipelines = app.getBean(EtlPipelineMapper.class); batches = app.getBean(EtlTargetBatchMapper.class);
        var connection = new EtlConnectionInfo(); connection.setDatabaseId(1L); connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        connection.setJdbcUrl(postgres.getJdbcUrl()); connection.setUsername(postgres.getUsername()); connection.setPassword(postgres.getPassword()); connection.setSchema(targetSchema);
        when(connections.resolve(1L)).thenReturn(connection);
        when(queries.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(qualityApi.validateForRun(anyLong(), anyLong())).thenReturn(CommonResult.success(true));
        TenantContextHolder.setTenantId(7L);
        sql("INSERT INTO dh_dataforge_etl_pipeline(id,name,task_type,status,schedule_status,revision,published_revision,schedule_generation,published_execution_user_id,published_config_json) VALUES(10,'test',1,1,0,1,1,1,99,'{\"name\":\"published\",\"nodes\":[],\"edges\":[],\"parameters\":[]}')");
    }

    void requireAtomicPostgres16() throws java.sql.SQLException {
        int major = control.getMetaData().getDatabaseMajorVersion();
        org.junit.jupiter.api.Assumptions.assumeTrue(major == 16,
                "DB Switch atomic target writes require PostgreSQL 16; available test server is " + major);
    }

    EtlRecordDO run(String requestId, boolean start) {
        var prepared = EtlRecordDO.builder().pipelineId(10L).pipelineRevision(1).name("published").executionUserId(99L)
                .qualityStatus("NOT_CONFIGURED").configJson("{\"name\":\"published\",\"nodes\":[],\"edges\":[],\"parameters\":[]}").build();
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared, requestId);
        if (start) {
            var lease = coordinator.claim(receipt.recordId(), "test-node").orElseThrow();
            assertTrue(coordinator.startEffects(receipt.recordId(), lease.token()));
        }
        return records.selectById(receipt.recordId());
    }
    void quality(EtlRecordDO run, String node, int total, int pass, int warn, int reject) throws Exception {
        sql("INSERT INTO dh_dataforge_etl_quality_batch(record_id,node_no,execution_user_id,plan_hash,input_count,pass_count,warn_count,reject_count,violation_count,registration_status,attempt_count) VALUES(" + run.getId() + ",'" + node + "',99,'frozen'," + total + "," + pass + "," + warn + "," + reject + "," + (warn + reject) + ",'PENDING',0)");
    }
    void migrate(String name) throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.baselineStructure(name)
                .replace("datahub-center", schema));
    }
    void sql(String sql) throws SQLException { try (var statement = control.createStatement()) { statement.execute(sql); } }
    String value(String sql) throws SQLException { try (var statement = control.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); } }
    @Override public void close() throws Exception {
        app.close(); runtime.close(); TenantContextHolder.clear();
        try { sql("DROP SCHEMA " + schema + " CASCADE"); sql("DROP SCHEMA " + targetSchema + " CASCADE"); } finally { control.close(); }
    }
}
