package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
class SafetyWritePermissionPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate jdbc; DriverManagerDataSource datasource; SqlSessionTemplate sessions;
    SafetyPermissionTestFixture fixture; SafetyPermissionService configuration; SafetyWritePermissionService writes;
    String migration;

    @BeforeEach void setup() throws Exception {
        String jdbcUrl = DB.getJdbcUrl();
        datasource = new DriverManagerDataSource(jdbcUrl+(jdbcUrl.contains("?")?"&":"?")+"currentSchema=datahub-center",DB.getUsername(),DB.getPassword());
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE;CREATE SCHEMA \"datahub-center\"");
        jdbc.execute("\n                CREATE SEQUENCE dh_governance_safety_table_permission_seq;\n                CREATE TABLE dh_governance_safety_table_permission(\n                id bigint PRIMARY KEY DEFAULT nextval('dh_governance_safety_table_permission_seq'),database_id bigint,table_name text,schema_name text,\n                binding_note text,binding_actor_id bigint,binding_time timestamp,role_id bigint,security_level_id bigint,permission_type integer,\n                row_filter_condition text,status integer,remark text,\n                creator varchar(64) DEFAULT '',create_time timestamp DEFAULT now(),updater varchar(64) DEFAULT '',update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0);\n                CREATE UNIQUE INDEX exact_write_policy ON dh_governance_safety_table_permission(database_id,schema_name,table_name,role_id) WHERE deleted=0;\n                CREATE TABLE dh_governance_safety_field_permission(id bigint PRIMARY KEY,database_id bigint,schema_name text,table_name text,\n                field_name text,role_id bigint,status integer,permission_type integer,masking_rule_id bigint,security_level_id bigint,\n                binding_note text,binding_actor_id bigint,binding_time timestamp,remark text,\n                creator varchar(64) DEFAULT '',create_time timestamp DEFAULT now(),updater varchar(64) DEFAULT '',update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0);\n                CREATE SEQUENCE system_menu_seq;\n                CREATE TABLE system_menu(id bigint PRIMARY KEY DEFAULT nextval('system_menu_seq'),name text,permission text,type integer,sort integer,parent_id bigint,\n                path text,icon text,component text,component_name text,status integer,visible boolean,keep_alive boolean,always_show boolean,\n                creator text,updater text,create_time timestamp,update_time timestamp,deleted smallint DEFAULT 0);\n                INSERT INTO system_menu(id,name,permission,type,component,status,deleted) VALUES(1,'数据权限','',2,'governance/permission/index',0,0);\n                INSERT INTO dh_governance_safety_table_permission(id,database_id,schema_name,table_name,role_id,permission_type,status)\n                VALUES(10,10,'public','orders',20,2,0),(20,11,'public','orders',20,2,0);\n                ");
        migration = Files.readString(repo("sql/postgresql/migrations/0100_governance_dml-authorization.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(jdbc, "datahub-center");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var interceptors = new MybatisPlusInterceptor();
        config.addInterceptor(interceptors); config.setEnvironment(new Environment("write-permission",new SpringManagedTransactionFactory(),datasource));
        config.addMapper(SafetyTablePermissionMapper.class); config.addMapper(SafetyFieldPermissionMapper.class); config.addMapper(SafetyWritePolicyAuditMapper.class);
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        fixture = new SafetyPermissionTestFixture();
        when(fixture.database.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of(new DatabaseColumnRespDTO("id","int8"),new DatabaseColumnRespDTO("status","varchar"),new DatabaseColumnRespDTO("district_code","varchar"))));
        var tables = sessions.getMapper(SafetyTablePermissionMapper.class); var fields = sessions.getMapper(SafetyFieldPermissionMapper.class);
        ReflectionTestUtils.setField(fixture.service,"tablePermissionMapper",tables); ReflectionTestUtils.setField(fixture.service,"fieldPermissionMapper",fields);
        var target = new SafetyPermissionServiceImpl(); ReflectionTestUtils.setField(target,"bindingResolver",fixture.resolver);
        ReflectionTestUtils.setField(target,"tablePermissionMapper",tables); ReflectionTestUtils.setField(target,"fieldPermissionMapper",fields);
        ReflectionTestUtils.setField(target,"writePolicyAuditMapper",sessions.getMapper(SafetyWritePolicyAuditMapper.class));
        ProxyFactory proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource),new AnnotationTransactionAttributeSource()));
        configuration = (SafetyPermissionService)proxy.getProxy();
        writes = new SafetyWritePermissionService(); ReflectionTestUtils.setField(writes,"bindingResolver",fixture.resolver);
        ReflectionTestUtils.setField(writes,"sqlPermissionService",fixture.service); ReflectionTestUtils.setField(writes,"tablePermissionMapper",tables);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void migrationReplayDoesNotGrantLegacyReadWriteAndPreservesOneMenu() {
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dh_governance_safety_table_permission WHERE permission_type=2 AND write_policy IS NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE permission='governance:safety-permission:write-preflight'",Integer.class));
        assertThrows(ServiceException.class,()->preflight());
    }
    @Test void grantAndRevocationPersistAuditAtomicallyWithoutTenantContext() {
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            configuration.saveConfig(save(true,"依据区政务局授权办理本区办件"));
            assertNotNull(preflight());
            TenantContextHolder.clear();
            assertNotNull(preflight());
            TenantContextHolder.clear();
            assertThrows(ServiceException.class,()->configuration.saveConfig(save(false,"")));
            assertNotNull(preflight());
            configuration.saveConfig(save(false,"区政务局申请撤销办件写权限"));
            assertThrows(ServiceException.class,()->preflight());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dh_governance_safety_write_audit WHERE actor_id=9",Integer.class));
            assertNull(jdbc.queryForObject("SELECT write_policy FROM dh_governance_safety_table_permission WHERE id=10",String.class));
            assertNull(jdbc.queryForObject("SELECT write_policy FROM dh_governance_safety_table_permission WHERE id=20",String.class));
        }
    }
    @Test void auditFailureRollsBackPermissionUpdateAndDoesNotLeaveGrant() {
        jdbc.execute("CREATE FUNCTION reject_write_audit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''unavailable''; END';CREATE TRIGGER reject_audit BEFORE INSERT ON dh_governance_safety_write_audit FOR EACH ROW EXECUTE FUNCTION reject_write_audit()");
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThrows(RuntimeException.class,()->configuration.saveConfig(save(true,"依据区政务局授权办理本区办件")));
            assertNull(jdbc.queryForObject("SELECT write_policy FROM dh_governance_safety_table_permission WHERE id=10",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dh_governance_safety_write_audit",Integer.class));
        }
    }
    private Object preflight() { return writes.preflight(10L,"UPDATE orders SET status=? WHERE id=?",List.of("DONE","1"),20L); }
    private SafetyPermissionSaveReqVO save(boolean grant,String reason) {
        var request=new SafetyPermissionSaveReqVO();request.setDatabaseId(10L);request.setSchemaName("public");request.setTableName("orders");request.setTableReference("public.orders");
        var role=new SafetyPermissionSaveReqVO.RolePermissionConfig();role.setRoleId(20L);role.setFieldPermissions(List.of());
        var table=new SafetyPermissionSaveReqVO.TablePermission();table.setPermissionType(1);table.setWritePolicy(grant?SafetyWritePermissionServiceTest.policy():null);table.setWriteChangeReason(reason);
        role.setTablePermission(table);request.setRolePermissionConfigs(List.of(role));return request;
    }
    private static Path repo(String relative) {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("sql/postgresql/migrations"))) root=root.getParent();
        if(root==null)throw new IllegalStateException("Repository not found");return root.resolve(relative);
    }
}
