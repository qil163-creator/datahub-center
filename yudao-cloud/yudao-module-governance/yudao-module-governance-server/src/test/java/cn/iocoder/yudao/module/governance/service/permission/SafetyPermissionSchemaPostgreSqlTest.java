package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyFieldPermissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyTablePermissionMapper;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionLegacyBindReqVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 0074 从旧表升级/重放及真实租户 Mapper；仅使用显式回环库中的随机自有 schema。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Timeout(30)
class SafetyPermissionSchemaPostgreSqlTest {
    private static final String TABLE = "dh_governance_safety_table_permission";
    private static final String FIELD = "dh_governance_safety_field_permission";
    private final String schema = "permission_binding_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String url;
    private boolean created;

    @BeforeEach void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Only an explicit loopback test database is allowed");
        }
        admin = new JdbcTemplate(source(null));
        admin.execute("CREATE SCHEMA " + schema);
        created = true;
        db = new JdbcTemplate(source(schema));
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(db, schema, List.of(TABLE, FIELD));

    }

    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        if (created) {
            assertThat(schema).matches("permission_binding_[a-f0-9]{32}");
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void currentSchemaRetainsUnboundPoliciesAcrossMigrationReplay() {
        db.update("INSERT INTO " + TABLE + "(id,database_id,table_name,role_id) VALUES(1,10,'orders',20)");
        db.update("INSERT INTO " + FIELD + "(id,database_id,table_name,field_name,role_id) VALUES(2,10,'orders','id',20)");
        migrateCurrent(); migrateCurrent();
        for (String name : List.of(TABLE, FIELD)) {
            for (String column : List.of("schema_name", "binding_note", "binding_actor_id", "binding_time")) {
                assertThat(columnCount(name, column)).isEqualTo(1);
            }
            assertThat(columnCount(name, "tenant_id")).isZero();
        }
        assertThat(sessions().getMapper(SafetyTablePermissionMapper.class).countActiveUnbound(10L)).isEqualTo(1);
        assertThat(sessions().getMapper(SafetyFieldPermissionMapper.class).countActiveUnbound(10L)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {TABLE, FIELD})
    void boundPoliciesRequireCompleteReviewEvidence(String name) {
        migrateCurrent();
        String columns = name.equals(FIELD) ? ",field_name" : "";
        String value = name.equals(FIELD) ? ",'id'" : "";
        for (String evidence : List.of("' ', 'review',9,now()", "'public',' ',9,now()",
                "'public','review',0,now()", "'public','review',NULL,now()", "'public','review',9,NULL")) {
            String sql = "INSERT INTO " + name + "(id,database_id,table_name,role_id,schema_name,binding_note,binding_actor_id,binding_time"
                    + columns + ") VALUES(1,10,'orders',20," + evidence + value + ")";
            assertSqlState(sql, "23514");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM " + name, Long.class)).isZero();
    }

    @Test void exactTableKeySeparatesSchemaCaseRoleAndDatasource() {
        migrateCurrent();
        insertTable(1, 10, "public", "orders", 20, 0, 0);
        assertSqlState(tableInsert(2, 10, "public", "orders", 20, 0, 0), "23505");
        assertSqlState(tableInsert(3, 10, "public", "orders", 20, 0, 0), "23505");
        insertTable(4, 10, "Other", "orders", 20, 0, 0);
        insertTable(5, 10, "Public", "orders", 20, 0, 0);
        insertTable(6, 11, "public", "orders", 20, 0, 0);
        insertTable(7, 10, "public", "orders", 21, 0, 0);
        insertTable(8, 10, "public", "Orders", 20, 0, 0);
        insertTable(9, 10, "public", "orders", 20, 0, 1);
        assertThat(db.queryForObject("SELECT count(*) FROM " + TABLE, Long.class)).isEqualTo(7);
    }

    @Test void fieldUniquenessIncludesExactPhysicalColumn() {
        migrateCurrent();
        insertField(1, "public", "id");
        assertSqlState(fieldInsert(2, "public", "id"), "23505");
        insertField(3, "public", "Id");
        insertField(4, "Other", "id");
        assertSqlState(fieldInsert(5, "public", "id"), "23505");
        assertThat(db.queryForObject("SELECT count(*) FROM " + FIELD, Long.class)).isEqualTo(3);
    }

    @Test void realMappersNeverMergeOtherSchemaOrCase() {
        migrateCurrent();
        insertTable(1, 10, "public", "orders", 20, 0, 0);
        insertTable(2, 11, "public", "orders", 20, 0, 0);
        insertTable(3, 10, "Other", "orders", 20, 0, 0);
        insertField(4, "public", "id");
        assertSqlState(fieldInsert(5, "public", "id"), "23505");
        insertField(6, "Other", "id");
        var sessions = sessions();
        var tables = sessions.getMapper(SafetyTablePermissionMapper.class);
        var fields = sessions.getMapper(SafetyFieldPermissionMapper.class);
        TenantContextHolder.clear();
        assertThat(tables.selectListByTableRole(10L, "public", "orders", List.of(20L))).extracting("id").containsExactly(1L);
        assertThat(tables.selectListByTableRole(10L, "Other", "orders", List.of(20L))).extracting("id").containsExactly(3L);
        assertThat(tables.selectListByTableRole(10L, "Public", "orders", List.of(20L))).isEmpty();
        assertThat(fields.selectActiveByTableRoles(10L, "public", "orders", List.of(20L))).extracting("id").containsExactly(4L);
        TenantContextHolder.clear();
        assertThat(tables.selectListByTableRole(10L, "public", "orders", List.of(20L))).extracting("id").containsExactly(1L);
        assertThat(fields.selectActiveByTableRoles(10L, "public", "orders", List.of(20L))).extracting("id").containsExactly(4L);
    }

    @Test void unboundDetectionExcludesInactiveDeletedAndOtherDatasource() {
        migrateCurrent();
        db.update("INSERT INTO " + TABLE + "(id,database_id,table_name,role_id,status,deleted) VALUES(1,10,'orders',20,0,0),(2,10,'orders',21,1,0),(3,10,'orders',22,0,1),(4,11,'orders',20,0,0)");
        TenantContextHolder.clear();
        assertThat(sessions().getMapper(SafetyTablePermissionMapper.class).countActiveUnbound(10L)).isEqualTo(1);
        TenantContextHolder.clear();
        assertThat(sessions().getMapper(SafetyTablePermissionMapper.class).countActiveUnbound(10L)).isEqualTo(1);
    }


    @Test void legacyBindingCommitsWholeGroupWithReviewButNeverAnotherDatasource() {
        prepareLegacyGroup();
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            legacyService().bindLegacy(bindingRequest());
        }
        for (String name : List.of(TABLE, FIELD)) {
            assertThat(db.queryForObject("SELECT count(*) FROM " + name + " WHERE schema_name='public' AND binding_actor_id=9 AND binding_time IS NOT NULL AND binding_note='verified exact target'", Long.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM " + name + " WHERE schema_name IS NULL", Long.class)).isEqualTo(1);
        }
        assertThat(sessions().getMapper(SafetyFieldPermissionMapper.class)
                .selectActiveByTableRoles(10L, "public", "orders", List.of(20L)))
                .extracting("permissionType").containsExactly(0);
    }

    @ParameterizedTest @ValueSource(strings = {TABLE, FIELD})
    void skippedBindingUpdateRollsBackTheEntirePolicyGroup(String name) {
        prepareLegacyGroup();
        db.execute("CREATE FUNCTION ignore_binding_write() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'");
        db.execute("CREATE TRIGGER ignore_binding_write BEFORE UPDATE ON " + name
                + " FOR EACH ROW EXECUTE FUNCTION ignore_binding_write()");
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> legacyService().bindLegacy(bindingRequest())).isInstanceOf(RuntimeException.class);
        }
        for (String table : List.of(TABLE, FIELD)) {
            assertThat(db.queryForObject("SELECT count(*) FROM " + table
                    + " WHERE schema_name IS NULL AND binding_time IS NULL AND binding_actor_id IS NULL", Long.class)).isEqualTo(2);
        }
    }

    private void migrateCurrent() {
        try { cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void prepareLegacyGroup() {
        migrateCurrent();
        db.execute("INSERT INTO " + TABLE + "(id,database_id,table_name,role_id,permission_type) VALUES(1,10,'orders',20,1),(2,11,'orders',20,0)");
        db.execute("INSERT INTO " + FIELD + "(id,database_id,table_name,field_name,role_id,permission_type) VALUES(3,10,'orders','id',20,0),(4,11,'orders','id',20,0)");
        TenantContextHolder.clear();
    }

    private SafetyPermissionService legacyService() {
        // 数据源核验 RPC 与登录身份明确使用替身；平台 Mapper、行锁及代理事务真实执行。
        var resolver = mock(SafetyPermissionBindingResolver.class);
        var table = new DatabaseQueryTableRespDTO();
        table.setSchemaName("public"); table.setTableName("orders"); table.setTableReference("public.orders");
        var column = new DatabaseColumnRespDTO(); column.setColumnName("id");
        var route = new SafetyPermissionBindingResolver.Route(10L, ProductTypeEnum.POSTGRESQL, "public", "verified-r1");
        when(resolver.verifiedTable(10L, "public.orders", "public", "orders")).thenReturn(
                new SafetyPermissionBindingResolver.VerifiedTable(route, table, List.of(column)));
        var datasource = source(schema);
        var sessions = sessions(datasource);
        var target = new SafetyPermissionServiceImpl();
        ReflectionTestUtils.setField(target, "bindingResolver", resolver);
        ReflectionTestUtils.setField(target, "tablePermissionMapper", sessions.getMapper(SafetyTablePermissionMapper.class));
        ReflectionTestUtils.setField(target, "fieldPermissionMapper", sessions.getMapper(SafetyFieldPermissionMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource),
                new AnnotationTransactionAttributeSource()));
        return (SafetyPermissionService) proxy.getProxy();
    }

    private SafetyPermissionLegacyBindReqVO bindingRequest() {
        var request = new SafetyPermissionLegacyBindReqVO();
        request.setDatabaseId(10L); request.setRoleId(20L); request.setLegacyTableName("orders");
        request.setSchemaName("public"); request.setTableName("orders"); request.setTableReference("public.orders");
        request.setNote("verified exact target");
        return request;
    }

    private void assertSqlState(String sql, String state) {
        try {
            db.execute(sql);
            fail("Expected PostgreSQL SQLSTATE " + state);
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
        }
    }

    private long columnCount(String table, String column) {
        return db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?",
                Long.class, schema, table, column);
    }

    private void insertTable(long id, long database, String binding, String table, long role, int status, int deleted) {
        db.execute(tableInsert(id, database, binding, table, role, status, deleted));
    }

    private String tableInsert(long id, long database, String binding, String table, long role, int status, int deleted) {
        return "INSERT INTO " + TABLE + "(id,database_id,schema_name,table_name,role_id,status,deleted,binding_note,binding_actor_id,binding_time) VALUES(%d,%d,'%s','%s',%d,%d,%d,'verified fixture',9,now())".formatted(id, database, binding, table, role, status, deleted);
    }

    private void insertField(long id, String binding, String field) { db.execute(fieldInsert(id, binding, field)); }
    private String fieldInsert(long id, String binding, String field) {
        return "INSERT INTO " + FIELD + "(id,database_id,schema_name,table_name,field_name,role_id,binding_note,binding_actor_id,binding_time) VALUES(%d,10,'%s','orders','%s',20,'verified fixture',9,now())".formatted(id, binding, field);
    }

    private SqlSessionTemplate sessions() {
        return sessions(source(schema));
    }

    private SqlSessionTemplate sessions(DriverManagerDataSource datasource) {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setDefaultStatementTimeout(10);
        var interceptors = new MybatisPlusInterceptor();
        config.addInterceptor(interceptors);
        config.setEnvironment(new Environment("permission-binding", new SpringManagedTransactionFactory(), datasource));
        config.addMapper(SafetyTablePermissionMapper.class);
        config.addMapper(SafetyFieldPermissionMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    }

    private DriverManagerDataSource source(String binding) {
        return new DriverManagerDataSource(url + (binding == null ? "" : "?currentSchema=" + binding), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
    }

    private static Path repo(String relative) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository migrations");
        return root.resolve(relative);
    }
}
