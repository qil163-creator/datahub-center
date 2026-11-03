package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.ModelMaterializedLogPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelDeploymentAttemptDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelDeploymentAttemptMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedLogMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** 隔离 PostgreSQL 中验证迁移重放、新库增量片段一致和真实日志写入读取的历史稳定性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelMaterializedLogSnapshotPostgreSqlTest {
    private static final String MIGRATION = "0211_modeling_materialized-log-snapshot.sql";
    private static final String TABLE = "dh_modeling_model_materialized_log";
    private Connection connection;
    private SqlSessionFactory sessions;
    private Path root;

    @BeforeAll void initialize() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]+/[A-Za-z0-9_]+$"),
                "需指定本次隔离的本机 PostgreSQL 实例");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        connection = DriverManager.getConnection(url, user, "");
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root);
        var properties = new Properties(); properties.setProperty("user", user); properties.setProperty("currentSchema", "datahub-center");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("materialized-log-test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", url, properties)));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(pagination); configuration.addMapper(ModelMaterializedMapper.class);
        configuration.addMapper(ModelMaterializedLogMapper.class); configuration.addMapper(ModelDeploymentAttemptMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach void fixture() throws Exception {
        execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", pg_catalog");
        execute("""
                CREATE SEQUENCE dh_modeling_model_materialized_log_seq START 501;
                CREATE TABLE dh_modeling_model_materialized_log (
                    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_model_materialized_log_seq'),
                    materialized_id bigint NOT NULL, model_id bigint NOT NULL, publish_mode varchar(32) NOT NULL,
                    status varchar(32) NOT NULL, ddl_sql text, message varchar(2000), operator varchar(64),
                    start_time timestamp, end_time timestamp, creator varchar(64), updater varchar(64),
                    create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP,
                    deleted smallint DEFAULT 0
                );
                CREATE TABLE dh_modeling_model_materialized (
                    id bigint PRIMARY KEY, model_id bigint, datasource_id bigint, version_id bigint, attempt_id bigint,
                    datasource_type text, datasource_name text, model_name text, model_alias text, schema_name text,
                    table_name text, publish_mode text, status text, ddl_sql text, message text, description text, remark text,
                    creator text, updater text, create_time timestamp, update_time timestamp, deleted smallint DEFAULT 0
                );
                CREATE TABLE dh_modeling_deployment_attempt (
                    id bigint PRIMARY KEY, materialized_id bigint, status text, phase text, lease_owner text,
                    lease_until timestamp, message text, finished_time timestamp, deleted smallint DEFAULT 0
                );
                """);
    }

    @AfterAll void cleanup() throws Exception {
        if (connection == null) return;
        try { execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE"); } finally { connection.close(); }
    }

    @Test void replayPreservesLegacyLogsAndLeavesUnknownSnapshotColumnsEmpty() throws Exception {
        execute("INSERT INTO dh_modeling_model_materialized_log(id, materialized_id, model_id, publish_mode, status, message) VALUES(500,10,2,'REBUILD','SUCCESS','原始历史日志')");
        migrate(); migrate();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE id=500")) {
            assertTrue(rows.next()); assertEquals("原始历史日志", rows.getString("message"));
            for (String column : List.of("attempt_id", "model_name", "model_alias", "datasource_id", "datasource_name", "datasource_type", "description"))
                assertNull(rows.getObject(column), column);
        }
    }

    @Test void realFinishAndReadRetainOriginalNamesAfterSameTargetRepublish() throws Exception {
        migrate();
        execute("INSERT INTO dh_modeling_model_materialized(id,model_id,datasource_id,attempt_id,datasource_type,datasource_name,model_name,model_alias,description,status) VALUES(10,2,7,100,'POSTGRESQL','原始连接名','first_model','原始模型名','第一次说明','RUNNING')");
        execute("INSERT INTO dh_modeling_deployment_attempt(id,materialized_id,status,lease_owner,lease_until) VALUES(100,10,'RUNNING','test-owner',CURRENT_TIMESTAMP+INTERVAL '5 minutes')");
        try (var session = sessions.openSession(false)) {
            var store = new ModelDeploymentAttemptStore();
            ReflectionTestUtils.setField(store, "attempts", session.getMapper(ModelDeploymentAttemptMapper.class));
            ReflectionTestUtils.setField(store, "materialized", session.getMapper(ModelMaterializedMapper.class));
            ReflectionTestUtils.setField(store, "logs", session.getMapper(ModelMaterializedLogMapper.class));
            store.finish(attempt(100L), "SUCCESS", "第一次完成"); session.commit();
        }
        execute("UPDATE dh_modeling_model_materialized SET attempt_id=101,model_name='second_model',model_alias='第二次模型名',datasource_name='第二次连接名',description='第二次说明',status='RUNNING' WHERE id=10");
        execute("INSERT INTO dh_modeling_deployment_attempt(id,materialized_id,status,lease_owner,lease_until) VALUES(101,10,'RUNNING','test-owner',CURRENT_TIMESTAMP+INTERVAL '5 minutes')");
        try (var session = sessions.openSession(false)) {
            var store = new ModelDeploymentAttemptStore();
            ReflectionTestUtils.setField(store, "attempts", session.getMapper(ModelDeploymentAttemptMapper.class));
            ReflectionTestUtils.setField(store, "materialized", session.getMapper(ModelMaterializedMapper.class));
            ReflectionTestUtils.setField(store, "logs", session.getMapper(ModelMaterializedLogMapper.class));
            store.finish(attempt(101L), "SUCCESS", "第二次完成"); session.commit();
            var service = new ModelMaterializedServiceImpl();
            ReflectionTestUtils.setField(service, "logMapper", session.getMapper(ModelMaterializedLogMapper.class));
            ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
            var request = new ModelMaterializedLogPageReqVO(); request.setModelId(2L);
            var page = service.getLogPage(request);
            assertEquals(2L, page.getTotal());
            var first = page.getList().stream().filter(log -> Long.valueOf(100L).equals(log.getAttemptId())).findFirst().orElseThrow();
            assertEquals("first_model", first.getModelName()); assertEquals("原始模型名", first.getModelAlias());
            assertEquals("原始连接名", first.getDatasourceName()); assertEquals("第一次说明", first.getDescription());
            assertEquals(7L, first.getDatasourceId()); assertEquals("POSTGRESQL", first.getDatasourceType());
        }
    }

    @Test void freshBaselineAffectedTableAndIncrementalUpgradeDefineIdenticalSnapshotColumns() throws Exception {
        migrate();
        List<String> upgraded = snapshotColumnSignatures();
        execute("DROP TABLE dh_modeling_model_materialized_log");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        assertTrue(baseline.startsWith("-- Included migrations through: 0211"));
        String createStart = "CREATE TABLE IF NOT EXISTS \"datahub-center\".\"" + TABLE + "\" (";
        int start = baseline.indexOf(createStart); int end = baseline.indexOf("\n);", start);
        assertTrue(start >= 0 && end > start);
        // 仅执行受影响表的新库建表片段与最终增量；不把局部一致性测试宣称为整库安装验证。
        execute(baseline.substring(start, end + 3));
        String marker = "-- 新库最终状态来源：" + MIGRATION;
        String tail = baseline.substring(baseline.indexOf(marker) + marker.length()).strip();
        assertEquals(migration().strip(), tail);
        execute(tail);
        assertEquals(upgraded, snapshotColumnSignatures());
    }

    private List<String> snapshotColumnSignatures() throws Exception {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, col_description(a.attrelid,a.attnum)
                FROM pg_attribute a WHERE a.attrelid='dh_modeling_model_materialized_log'::regclass AND NOT a.attisdropped
                  AND a.attname IN ('attempt_id','model_name','model_alias','datasource_id','datasource_name','datasource_type','description')
                ORDER BY a.attname
                """)) {
            while (rows.next()) result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getBoolean(3) + "|" + rows.getString(4));
        }
        assertEquals(7, result.size()); return result;
    }

    private ModelDeploymentAttemptDO attempt(Long id) {
        var result = new ModelDeploymentAttemptDO(); result.setId(id); result.setMaterializedId(10L);
        result.setLeaseOwner("test-owner"); result.setPublishMode("REBUILD"); result.setCreateTime(LocalDateTime.now().minusSeconds(2));
        return result;
    }

    private String migration() throws Exception { return Files.readString(root.resolve("sql/postgresql/migrations/" + MIGRATION)); }
    private void migrate() throws Exception { execute(migration()); }
    private void execute(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
}
