package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetMapPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 验证用户、部门范围和分页分组总数；数据库仅限本测试自建的随机库。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetMapMapperPostgreSqlTest {
    private String controlUrl;
    private String databaseName;
    private Connection connection;
    private SqlSession session;
    private DataAssetMapper mapper;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        databaseName = "asset_map_scope_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + databaseName);
        }
        String jdbcUrl = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName;
        connection = DriverManager.getConnection(jdbcUrl, "catalog_test", "");
        var columns = new ArrayList<String>();
        for (Class<?> type = DataAssetDO.class; type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
                String sqlType = field.getType() == Long.class ? "bigint" : field.getType() == Integer.class ? "integer"
                        : field.getType() == LocalDateTime.class ? "timestamp"
                        : field.getType() == Boolean.class ? "boolean" : field.getType() == java.math.BigDecimal.class ? "numeric" : "text";
                if (name.equals("deleted")) sqlType = "smallint DEFAULT 0";
                if (name.equals("retirement_requested")) sqlType = "boolean DEFAULT false";
                columns.add(name + " " + sqlType + (name.equals("id") ? " PRIMARY KEY" : ""));
            }
        }
        sql("CREATE TABLE dh_asset_data_asset (" + String.join(",", columns) + ")");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("map-scope", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", jdbcUrl, "catalog_test", "")));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        config.addMapper(DataAssetMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(DataAssetMapper.class);
    }

    @BeforeEach
    void prepareScopeFixture() throws Exception {
        session.clearCache();
        sql("TRUNCATE dh_asset_data_asset");
        sql("""
                INSERT INTO dh_asset_data_asset(id,asset_name,status,asset_type,category_id,layer,tech_leader_user_id,
                    owner_dept_id,provider_dept_id,creator,deleted) VALUES
                    (1,'本人草稿',0,1,10,'OWN',9,NULL,NULL,'88',0),
                    (2,'本部门草稿',0,1,20,'DEPT',NULL,4,NULL,'88',0),
                    (3,'本部门供给',0,1,20,'DEPT',NULL,NULL,4,'88',0),
                    (4,'他人私有草稿',0,1,30,'PRIVATE',88,8,8,'88',0),
                    (5,'他人消费目录',1,2,30,'PRIVATE',NULL,NULL,NULL,NULL,0),
                    (6,'未授权发布',1,1,30,'PRIVATE',88,8,8,'88',0),
                    (7,'空部门草稿',0,1,30,'PRIVATE',NULL,NULL,NULL,NULL,0),
                    (8,'本人创建草稿',0,1,10,'OWN',NULL,NULL,NULL,'9',0),
                    (9,'已删除本人草稿',0,1,10,'OWN',9,4,4,'9',1);
                INSERT INTO dh_asset_data_asset(id,asset_name,status,category_id,layer,business_leader_user_id)
                    VALUES(10,'本人业务负责',0,10,'OWN',9);
                """);
    }

    @Test
    void pageTotalAndFacetsShareOwnerAndDepartmentScope() {
        var request = new DataAssetPageReqVO(); request.setPageSize(2);
        var page = mapper.selectMapPage(request, null, null, 9L, 4L);
        assertThat(page.getTotal()).isEqualTo(5);
        assertThat(page.getList()).hasSize(2);
        var all = new DataAssetPageReqVO(); all.setPageSize(100);
        assertThat(mapper.selectMapPage(all, null, null, 9L, 4L).getList())
                .extracting(DataAssetDO::getId).containsExactlyInAnyOrder(1L, 2L, 3L, 8L, 10L);
        var facets = mapper.selectMapFacetCounts(9L, 4L);
        assertThat(count(facets, "total", null)).isEqualTo(5);
        assertThat(count(facets, "category", "10")).isEqualTo(3);
        assertThat(count(facets, "category", "20")).isEqualTo(2);
        assertThat(count(facets, "category", "30")).isZero();
        assertThat(count(facets, "layer", "PRIVATE")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"9,NULL,3", "9,4,5", "99,NULL,0", "99,4,2"})
    void userAndDepartmentFieldsDefineTheSamePageAndFacetScope(long userId, String department, long total) {
        Long deptId = department.equals("NULL") ? null : Long.valueOf(department);
        var page = mapper.selectMapPage(new DataAssetPageReqVO(), null, null, userId, deptId);
        assertThat(page.getTotal()).isEqualTo(total);
        assertThat(page.getList()).extracting(DataAssetDO::getId).doesNotContain(5L, 6L, 7L, 9L);
        assertThat(count(mapper.selectMapFacetCounts(userId, deptId), "total", null)).isEqualTo(total);
    }

    @Test
    void clientFiltersCanOnlyNarrowTheUserAndDepartmentScope() {
        var request = new DataAssetPageReqVO(); request.setKeyword("本人"); request.setLayer("OWN"); request.setStatus(0);
        assertThat(mapper.selectMapPage(request, Set.of(10L), List.of(1L), 9L, 4L).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L);
        request.setKeyword("他人私有草稿"); request.setLayer("PRIVATE");
        assertThat(mapper.selectMapPage(request, Set.of(30L), List.of(4L), 9L, 4L).getTotal()).isZero();
    }

    @Test
    void boardFilterUsesExactRegistrationIdWithoutExpandingScope() throws Exception {
        sql("""
                UPDATE dh_asset_data_asset SET registration_config_json = CASE id
                  WHEN 1 THEN '{"dataDomainId":9007199254740993}'
                  WHEN 2 THEN '{"dataDomainId":"9007199254740993"}'
                  WHEN 3 THEN '{"dataDomainId":9007199254740994}'
                  WHEN 4 THEN '{"dataDomainId":9007199254740993}'
                  WHEN 8 THEN '{broken'
                  ELSE NULL END;
                """);
        var request = new DataAssetMapPageReqVO();
        request.setPageSize(100);
        request.setDataDomainId(9007199254740993L);
        assertThat(mapper.selectMapPage(request, null, null, 9L, 4L).getList())
                .extracting(DataAssetDO::getId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(mapper.selectMapPage(request, Set.of(10L), null, 9L, 4L).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L);
        request.setDataDomainId(9007199254740994L);
        assertThat(mapper.selectMapPage(request, null, null, 9L, 4L).getList())
                .extracting(DataAssetDO::getId).containsExactly(3L);
    }

    @Test
    void ownerVisibilityDoesNotDependOnPublicationOrConsumptionGrants() throws Exception {
        sql("UPDATE dh_asset_data_asset SET status=2 WHERE id=1");
        var request = new DataAssetPageReqVO(); request.setStatus(2);
        assertThat(mapper.selectMapPage(request, null, null, 9L, null).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L);
        request.setStatus(1);
        assertThat(mapper.selectMapPage(request, null, null, 9L, null).getTotal()).isZero();
    }

    private long count(List<Map<String, Object>> rows, String kind, String key) {
        return rows.stream().filter(row -> kind.equals(row.get("kind")) && Objects.equals(key, row.get("group_key")))
                .mapToLong(row -> ((Number) row.get("cnt")).longValue()).sum();
    }

    private void sql(String statement) throws Exception {
        try (var sql = connection.createStatement()) { sql.execute(statement); }
        if (session != null) session.clearCache();
    }

    @AfterAll
    void dropOwnedDatabase() throws Exception {
        if (session != null) session.close();
        if (connection != null) connection.close();
        if (databaseName != null && databaseName.matches("asset_map_scope_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }
}
