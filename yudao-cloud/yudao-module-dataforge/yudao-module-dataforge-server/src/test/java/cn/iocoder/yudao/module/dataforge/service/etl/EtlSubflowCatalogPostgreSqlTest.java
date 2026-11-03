package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实JSONB/EXISTS/分页及租户插件验证，不向H2推断平台查询。 */
class EtlSubflowCatalogPostgreSqlTest {
    private static PostgreSQLContainer<?> postgres;
    private static boolean external;
    private EtlBatchPgFixture pg;
    private EtlSubflowService service;
    private ExecutionAccessService access;

    @BeforeAll static void database() {
        String url = System.getProperty("datahub.test.postgres.url");
        external = url != null && !url.isBlank();
        if (external) postgres = new ExternalPostgres(url);
        else { postgres = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); postgres.start(); }
    }
    @AfterAll static void stopDatabase() { if (postgres != null && !external) postgres.stop(); }
    private static final class ExternalPostgres extends PostgreSQLContainer<ExternalPostgres> {
        private final String url;
        ExternalPostgres(String url) { super("postgres:16-alpine"); this.url = url; }
        @Override public String getJdbcUrl() { return url; }
        @Override public String getUsername() { return System.getProperty("datahub.test.postgres.user", "etl_fixture"); }
        @Override public String getPassword() { return System.getProperty("datahub.test.postgres.password", ""); }
    }

    @BeforeEach void setup() throws Exception {
        pg = new EtlBatchPgFixture(postgres);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("subflow-catalog", new SpringManagedTransactionFactory(), pg.platform));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(pg.platform);
        var interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor); config.addMapper(EtlPipelineMapper.class); config.addMapper(EtlVersionMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var versions = new EtlVersionService();
        ReflectionTestUtils.setField(versions, "pipelines", session.getMapper(EtlPipelineMapper.class));
        ReflectionTestUtils.setField(versions, "versions", session.getMapper(EtlVersionMapper.class));
        service = new EtlSubflowService(); access = mock(ExecutionAccessService.class);
        ReflectionTestUtils.setField(service, "pipelines", session.getMapper(EtlPipelineMapper.class));
        ReflectionTestUtils.setField(service, "versions", versions); ReflectionTestUtils.setField(service, "access", access);
        pg.sql("INSERT INTO dh_dataforge_etl_pipeline(id,name,task_type,status,revision,deleted) VALUES(11,'convert-orders',1,0,1,0),(12,'regular-flow',1,1,1,0),(13,'restore-only',1,0,1,0),(14,'deleted-flow',1,1,1,1),(15,'other-tenant',1,1,1,0),(16,'cross-tenant-version',1,1,1,0)");
        version(101,11,7,"PUBLISH",true,0); version(102,11,7,"RESTORE",true,0);
        version(103,12,7,"PUBLISH",false,0); version(104,13,7,"RESTORE",true,0);
        version(105,14,7,"PUBLISH",true,0); version(106,15,8,"PUBLISH",true,0);
        version(107,16,8,"PUBLISH",true,0); version(108,11,7,"PUBLISH",true,1);
        version(9007199254740993L,11,7,"PUBLISH",true,0);
    }
    @AfterEach void close() throws Exception { if (pg != null) pg.close(); }

    @Test void searchesPublishedDefinitionsEvenWithNewerDraft() {
        var req = new EtlSubflowPipelinePageReqVO(); req.setName("orders"); req.setPageSize(1);
        var result = service.pipelinePage(req, 99L);
        assertEquals(1, result.getTotal()); assertEquals(11L, result.getList().getFirst().getId());
        assertEquals("convert-orders", result.getList().getFirst().getName());
        verify(access).requireUserPermission(99L, "dataforge:etl:query");
        req.setName(null); assertEquals(3, service.pipelinePage(req,99L).getTotal());
    }

    @Test void versionPaginationRejectsRestoreOrdinaryAndDeletedVersions() {
        var req = new EtlSubflowVersionPageReqVO(); req.setPipelineId(11L); req.setPageSize(1);
        var first = service.versionPage(req,99L); assertEquals(2, first.getTotal());
        assertEquals(9007199254740993L, first.getList().getFirst().getId());
        assertEquals("PUBLISH", first.getList().getFirst().getEventType()); assertNull(first.getList().getFirst().getConfigJson());
        req.setPageNo(2); assertEquals(101L, service.versionPage(req,99L).getList().getFirst().getId());
        req.setPipelineId(12L); assertTrue(service.versionPage(req,99L).getList().isEmpty());
        req.setPageNo(1); req.setPipelineId(16L); assertEquals(1,service.versionPage(req,99L).getList().size());
        req.setPipelineId(15L); assertEquals(1,service.versionPage(req,99L).getList().size());
    }

    @Test void globalCatalogDoesNotRequireTenantContext() {
        TenantContextHolder.clear();
        var result = service.pipelinePage(new EtlSubflowPipelinePageReqVO(),99L);
        assertEquals(java.util.Set.of(11L,15L,16L), new java.util.HashSet<>(result.getList().stream().map(EtlSubflowPipelineRespVO::getId).toList()));
    }

    @Test void deniedActorAndUnboundedRequestsAreRejectedBeforeListing() {
        doThrow(new SecurityException("denied")).when(access).requireUserPermission(100L,"dataforge:etl:query");
        assertThrows(SecurityException.class, () -> service.pipelinePage(new EtlSubflowPipelinePageReqVO(),100L));
        var req = new EtlSubflowPipelinePageReqVO(); req.setPageSize(-1);
        assertThrows(RuntimeException.class, () -> service.pipelinePage(req,99L));
        req.setPageSize(51); assertThrows(RuntimeException.class, () -> service.pipelinePage(req,99L));
        var versions = new EtlSubflowVersionPageReqVO(); versions.setPipelineId(11L); versions.setPageSize(-1);
        assertThrows(RuntimeException.class, () -> service.versionPage(versions,99L));
    }

    private void version(long id,long pipeline,long tenant,String event,boolean definition,int deleted) throws Exception {
        try (var statement = pg.control.prepareStatement("INSERT INTO dh_dataforge_etl_version"
                + "(id,pipeline_id,revision,event_type,content_hash,config_json,actor_id,reason,deleted,release_generation,source_version_id)"
                + "VALUES(?,?,1,?,'fixture',?,99,'fixture',?,?,?)")) {
            statement.setLong(1,id); statement.setLong(2,pipeline); statement.setString(3,event);
            statement.setString(4,"{\"subflowDefinition\":"+definition+",\"nodes\":[],\"parameters\":[]}");
            statement.setInt(5,deleted);
            if ("PUBLISH".equals(event)) { statement.setLong(6,id); statement.setNull(7,java.sql.Types.BIGINT); }
            else { statement.setNull(6,java.sql.Types.BIGINT); statement.setLong(7,101); }
            statement.executeUpdate();
        }
    }
}
