package cn.iocoder.yudao.module.asset.service.term;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.term.AssetTermColumnRelMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_REVISION_CONFLICT;
import static org.junit.jupiter.api.Assertions.*;

/** 显式临时实例、随机 schema，验证无租户上下文的真实 Mapper 和经过 Spring 代理的事务。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "STANDARD_TERM_TEST_JDBC_URL", matches = ".+")
class AssetTermBindingPostgreSqlTest {

    private JdbcTemplate control;
    private JdbcTemplate jdbc;
    private String schema;
    private DataAssetMapper assets;
    private AssetTermBindingWriter writer;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("STANDARD_TERM_TEST_JDBC_URL");
        String user = System.getenv("STANDARD_TERM_TEST_JDBC_USER");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/(codex_term_[a-z_]+|postgres)"));
        assertTrue(java.util.Set.of("codex_term", "sharing_test").contains(user));
        control = new JdbcTemplate(new DriverManagerDataSource(url, user, ""));
        schema = "asset_term_test_" + UUID.randomUUID().toString().replace("-", "");
        control.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema + "&socketTimeout=10", user, "");
        jdbc = new JdbcTemplate(source);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_asset_data_asset", "dh_asset_term_column_rel")) {
            jdbc.execute("CREATE SEQUENCE " + table + "_seq");
            var matcher = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table
                    + "\" \\(.*?\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
            assertTrue(matcher.find(), "baseline table " + table);
            jdbc.execute(matcher.group().replace("\"datahub-center\".", ""));
            jdbc.execute("ALTER TABLE " + table + " ADD PRIMARY KEY(id)");
        }
        jdbc.execute("CREATE UNIQUE INDEX term_column_active ON dh_asset_term_column_rel(asset_id,meta_column_id) WHERE deleted=0");
        // 基线在后续增量段添加目录版本与提供部门，这里只补当前写入守卫读取的列。
        jdbc.execute("ALTER TABLE dh_asset_data_asset ADD COLUMN IF NOT EXISTS catalog_revision bigint DEFAULT 0, ADD COLUMN IF NOT EXISTS provider_dept_id bigint, ADD COLUMN IF NOT EXISTS stop_supply_user_id bigint, ADD COLUMN IF NOT EXISTS stop_supply_dept_id bigint, ADD COLUMN IF NOT EXISTS stop_supply_reason varchar(500), ADD COLUMN IF NOT EXISTS stop_supply_time timestamp, ADD COLUMN IF NOT EXISTS retirement_requested boolean DEFAULT false");
        jdbc.execute("""
                INSERT INTO dh_asset_data_asset(id,asset_code,catalog_item_urn,asset_name,
                    meta_table_id,resource_urn,published_snapshot_id,published_version,datasource_id,
                    table_name,catalog_revision,owner_dept_id)
                VALUES (1,'test','urn:test:asset','test',2,'urn:test:table',2,'1',1,'test',0,10)
                """);

        jdbc.execute(Files.readString(root.resolve("sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql")).replace("datahub-center", schema));
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("asset-term-test", new SpringManagedTransactionFactory(), source));
        var plugins = new MybatisPlusInterceptor();
        config.addInterceptor(plugins);
        config.addMapper(DataAssetMapper.class);
        config.addMapper(AssetTermColumnRelMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        assets = sessions.getMapper(DataAssetMapper.class);
        var target = new AssetTermBindingWriter();
        ReflectionTestUtils.setField(target, "assets", assets);
        ReflectionTestUtils.setField(target, "relations", sessions.getMapper(AssetTermColumnRelMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        writer = (AssetTermBindingWriter) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (schema != null) control.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void bindReplaceClearAndRebindPreserveGovernanceId() {
        var observed = assets.selectForCatalogUpdate(1L);
        var request = request(9007199254740993L);
        request.setRemark("first");
        writer.bind(observed, request, "customer_id");
        assertEquals(9007199254740993L, activeId());
        request.setTermId(9007199254740994L);
        request.setRemark(null);
        writer.bind(observed, request, "customer_id");
        assertEquals(9007199254740994L, activeId());
        assertNull(jdbc.queryForObject("SELECT remark FROM dh_asset_term_column_rel WHERE deleted=0", String.class));
        request.setTermId(null);
        writer.bind(observed, request, null);
        writer.bind(observed, request, null);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dh_asset_term_column_rel WHERE deleted=0", Integer.class));
        request.setTermId(9L);
        writer.bind(observed, request, "customer_id");
        assertEquals(9L, activeId());
    }

    @Test
    void changedOwnershipCannotWriteWithoutTenantContext() {
        var observed = assets.selectForCatalogUpdate(1L);
        jdbc.execute("UPDATE dh_asset_data_asset SET owner_dept_id=11 WHERE id=1");
        assertEquals(DATA_ASSET_CATALOG_REVISION_CONFLICT.getCode(),
                assertThrows(ServiceException.class, () -> writer.bind(observed, request(8L), "id")).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dh_asset_term_column_rel", Integer.class));
    }

    @Test
    void simultaneousFirstBindingsSerializeIntoOneActiveRelationship() throws Exception {
        var observed = assets.selectForCatalogUpdate(1L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(8L, 9L).stream().map(id -> pool.submit(() -> {
                TenantContextHolder.clear();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    writer.bind(observed, request(id), "customer_id");
                } finally {
                    TenantContextHolder.clear();
                }
                return true;
            })).toList();
            start.countDown();
            for (var task : tasks) assertTrue(task.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_asset_term_column_rel WHERE deleted=0", Integer.class));
        assertTrue(List.of(8L, 9L).contains(activeId()));
    }

    private Long activeId() {
        return jdbc.queryForObject("SELECT standard_term_id FROM dh_asset_term_column_rel WHERE deleted=0", Long.class);
    }

    private static AssetTermBindReqVO request(Long id) {
        var request = new AssetTermBindReqVO();
        request.setAssetId(1L);
        request.setMetaColumnId(3L);
        request.setTermId(id);
        return request;
    }
}
