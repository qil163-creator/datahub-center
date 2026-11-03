package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用本机专用 PostgreSQL 临时数据库验证生产 Mapper 的 CAS 和真实诊断调用链。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class DatabaseDiagnosisPersistencePostgreSqlTest {
    private static final long ID = 9007199254740993L;
    private String controlUrl;
    private String database;
    private String url;
    private Path root;
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;

    @BeforeAll
    void initialize() throws Exception {
        controlUrl = System.getenv("DATABASE_DIAGNOSIS_TEST_JDBC_URL");
        assumeTrue(controlUrl != null && !controlUrl.isBlank(), "需要显式提供本机隔离 PostgreSQL 测试环境");
        if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalStateException("仅接受任务专用本机 PostgreSQL 控制库");
        }
        database = "diagnosis_cas_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(controlUrl, "overview_test", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=datahub-center", "overview_test", ""));
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        jdbc.execute(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
        jdbc.execute("CREATE SCHEMA diagnosis_target");
        jdbc.execute("CREATE TABLE diagnosis_target.orders(id integer PRIMARY KEY,amount numeric(18,2))");
        jdbc.update("INSERT INTO diagnosis_target.orders VALUES(1,36.50)");

        var properties = new Properties();
        properties.setProperty("user", "overview_test");
        properties.setProperty("password", "");
        properties.setProperty("currentSchema", "datahub-center");
        var source = new UnpooledDataSource("org.postgresql.Driver", url, properties);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("diagnosis-test", new JdbcTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(DatabaseMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void seedSource() {
        jdbc.update("DELETE FROM dh_dataforge_database WHERE id=?", ID);
        jdbc.update("INSERT INTO dh_dataforge_database(id,name,database_type,database_ip,database_port," +
                "database_name,database_schema,user_name,password,secret_key,jdbc_url,status,connection_status," +
                "create_time,update_time,deleted) VALUES(?,'诊断并发样例',1,'127.0.0.1','55473','test','public'," +
                "'fixture_user','fixture_cipher','fixture_key','jdbc:postgresql://127.0.0.1:55473/test',0,0,now(),now(),0)", ID);
    }

    @Test
    void laterStartedResultCannotBeOverwrittenByAnOlderCompletion() {
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(DatabaseMapper.class);
            var service = persistenceService(mapper);
            var snapshot = mapper.selectById(ID);
            LocalDateTime earlier = mapper.selectDiagnosticStartTime();
            LocalDateTime later = earlier.plusSeconds(1);
            service.persistConnectionResult(snapshot, later, 1, "新诊断成功");
            service.persistConnectionResult(snapshot, earlier, 2, "旧诊断失败");
            var saved = mapper.selectById(ID);
            assertEquals(1, saved.getConnectionStatus());
            assertEquals("新诊断成功", saved.getLastTestMsg());
            assertEquals(later, saved.getLastTestTime());
        }
    }

    @Test
    void changedConfigurationAndSoftDeletionRejectStaleResults() {
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(DatabaseMapper.class);
            var service = persistenceService(mapper);
            var snapshot = mapper.selectById(ID);
            var started = mapper.selectDiagnosticStartTime();
            jdbc.update("UPDATE dh_dataforge_database SET database_ip='changed.invalid' WHERE id=?", ID);
            service.persistConnectionResult(snapshot, started, 1, "过期成功");
            assertNull(mapper.selectById(ID).getLastTestTime());
            jdbc.update("UPDATE dh_dataforge_database SET database_ip='127.0.0.1',deleted=1 WHERE id=?", ID);
            service.persistConnectionResult(snapshot, started.plusSeconds(1), 1, "已删除对象");
            assertNull(jdbc.queryForObject("SELECT last_test_time FROM dh_dataforge_database WHERE id=?", LocalDateTime.class, ID));
        }
    }

    @Test
    void formDiagnosisUsesRealPostgreSqlAndDoesNotWriteSavedStateOrBusinessRows() throws Exception {
        try (var session = sessions.openSession(true); var runtime = DbSwitchRuntime.standalone()) {
            var mapper = session.getMapper(DatabaseMapper.class);
            var service = persistenceService(mapper);
            var security = mock(SecurityFrameworkService.class);
            when(security.hasPermission(any())).thenReturn(true);
            var resolver = mock(AccessDatabaseResolver.class);
            when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(service, "securityFrameworkService", security);
            ReflectionTestUtils.setField(service, "accessDatabaseResolver", resolver);
            ReflectionTestUtils.setField(service, "databaseTypeMapper", mock(DatabaseTypeMapper.class));
            ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);

            var connection = new DatabaseSaveReqVO();
            connection.setDatabaseType(1);
            connection.setDatabaseIp("127.0.0.1");
            connection.setDatabasePort(controlUrl.substring(controlUrl.lastIndexOf(':') + 1, controlUrl.lastIndexOf('/')));
            connection.setDatabaseName(database);
            connection.setDatabaseSchema("diagnosis_target");
            connection.setUserName("overview_test");
            connection.setPassword("");
            connection.setJdbcUrl(url);
            var request = new DatabaseDiagnosisReqVO();
            request.setConnection(connection);

            var readonly = service.diagnoseDatabase(request);
            assertTrue(readonly.isConnectionSuccessful());
            assertEquals(6, readonly.getGroups().size());
            assertReferenceCatalog(readonly);
            var writes = readonly.getGroups().stream().filter(group -> group.getGroupCode().equals("WRITE")).findFirst().orElseThrow();
            assertTrue(writes.getItems().stream().allMatch(item -> item.getStatus().equals("SKIPPED")));
            assertCounts(readonly);

            request.setIncludeWriteChecks(true);
            var writable = service.diagnoseDatabase(request);
            assertTrue(writable.isConnectionSuccessful());
            assertReferenceCatalog(writable);
            assertTrue(writable.getGroups().stream().filter(group -> group.getGroupCode().equals("WRITE"))
                    .flatMap(group -> group.getItems().stream()).anyMatch(item -> item.getStatus().equals("PASS")));
            assertCounts(writable);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM diagnosis_target.orders", Integer.class));
            assertEquals("36.50", jdbc.queryForObject("SELECT amount::text FROM diagnosis_target.orders WHERE id=1", String.class));
            assertNull(mapper.selectById(ID).getLastTestTime());

            Path output = root.resolve("target/connection-diagnosis");
            Files.createDirectories(output);
            var json = JsonMapper.builder().addModule(new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration()
                    .timestampSupportModuleBean()).build();
            Files.writeString(output.resolve("real-readonly-response.json"), json.writeValueAsString(readonly));
            Files.writeString(output.resolve("real-write-response.json"), json.writeValueAsString(writable));
        }
    }

    private void assertCounts(DatabaseDiagnosisRespVO result) {
        assertEquals(result.getTotalCount(), result.getNormalCount() + result.getWarningCount()
                + result.getErrorCount() + result.getSkippedCount());
        assertEquals(result.getTotalCount(), result.getGroups().stream().mapToInt(group -> group.getItems().size()).sum());
    }

    private void assertReferenceCatalog(DatabaseDiagnosisRespVO result) {
        assertEquals(17, result.getTotalCount());
        assertEquals(List.of(4, 2, 3, 1, 2, 5), result.getGroups().stream().map(group -> group.getItems().size()).toList());
        assertEquals(List.of("基础连接", "目标资源访问", "元数据能力", "读取能力", "写入能力", "高级操作能力"),
                result.getGroups().stream().map(DatabaseDiagnosisRespVO.Group::getGroupName).toList());
        assertEquals(List.of("连接配置", "网络地址解析", "端口连通性", "账号认证", "目标资源访问", "账号角色与权限",
                        "元数据读取", "字段结构读取", "扩展属性读取", "库表读取权限", "INSERT写入", "批量写入",
                        "UPDATE/UPSERT/MERGE", "TRUNCATE/DELETE", "CREATE建表", "ALTER/RENAME/DROP", "CDC日志与复制权限"),
                result.getGroups().stream().flatMap(group -> group.getItems().stream())
                        .map(DatabaseDiagnosisRespVO.Item::getCheckName).toList());
    }

    private DatabaseServiceImpl persistenceService(DatabaseMapper mapper) {
        var service = new DatabaseServiceImpl();
        ReflectionTestUtils.setField(service, "databaseMapper", mapper);
        return service;
    }

    @AfterAll
    void close() throws Exception {
        if (database != null && database.matches("diagnosis_cas_[0-9a-f]{32}")) {
            try (var connection = DriverManager.getConnection(controlUrl, "overview_test", "");
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }
}
