package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyEtlGrantAuditPageReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyEtlGrantAuditDO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyEtlGrantAuditMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SupplyEtlGrantHistoryPostgreSqlTest extends cn.iocoder.yudao.module.supply.service.app.SharingPostgresSupport {
    @Override protected boolean useLegacyFixture() { return false; }
    private Connection control;
    private String schema;
    private SupplyEtlGrantAuditMapper mapper;
    private final SupplyEtlGrantHistoryService service = new SupplyEtlGrantHistoryService();

    @BeforeEach void setup() throws Exception {
        schema = "etl_history_" + UUID.randomUUID().toString().replace("-", "");
        control = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root);
        String migration = migrationSql("0097_supply_etl-grant-audit.sql")
                .replace("\"datahub-center\"", "\"" + schema + "\"");
        sql(migration); sql(migration);
        sql(migrationSql("0169_datahub_remove-business-tenancy.sql")
                .replace("datahub-center", schema));
        var ds = new DriverManagerDataSource(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                jdbcUser, jdbcPassword);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("etl-history", new SpringManagedTransactionFactory(), ds));
        config.addMapper(SupplyEtlGrantAuditMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(SupplyEtlGrantAuditMapper.class);
        ReflectionTestUtils.setField(service, "audits", mapper);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }

    @AfterEach void cleanup() throws Exception {
         SecurityContextHolder.clearContext();
        if (control != null) {
            try { if (schema != null && schema.matches("etl_history_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
            finally { control.close(); }
        }
    }

    @Test void descendingCursorSurvivesConcurrentAppendAndKeepsPreciseTypedSnapshots() {
        append(10L, 30L, 0L); append(10L, 30L, 1L); append(10L, 30L, 2L);
        var request = request(10L, 2);
        var page = service.history(request);
        assertTrue(page.hasMore()); assertEquals(List.of(2L, 1L), page.list().stream().map(i -> i.getRevision()).toList());
        var snapshot = page.list().getFirst().getAfter();
        assertEquals(9007199254740993L, snapshot.getPermissionUserId()); assertEquals(1, snapshot.getForeverFlag());
        assertEquals(LocalDateTime.of(2026, 10, 3, 10, 20), snapshot.getValidFrom());
        assertNotNull(page.list().getFirst().getCreateTime()); assertNull(page.list().getFirst().getBefore().getPermissionUserId());
        append(10L, 30L, 3L);
        request.setBeforeId(page.nextBeforeId());
        var tail = service.history(request);
        assertFalse(tail.hasMore()); assertNull(tail.nextBeforeId());
        assertEquals(List.of(0L), tail.list().stream().map(i -> i.getRevision()).toList());
    }

    @Test void appAndGrantFiltersNeverReadOtherHistories() {
        append(10L, 30L, 0L);
        append(11L, 32L, 0L); append(10L, 31L, 0L);
        var req = request(10L, 20); req.setGrantId(30L);
        var page = service.history(req);
        assertEquals(1, page.list().size()); assertEquals(30L, page.list().getFirst().getGrantId());
        req.setGrantId(999L); assertTrue(service.history(req).list().isEmpty());
        req.setAppId(999L); req.setGrantId(null); assertTrue(service.history(req).list().isEmpty());
        assertEquals(2, service.history(request(10L, 20)).list().size());
    }

    @Test void missingLoginAndUnboundedQueriesAreRejected() {
        var req = request(10L, 51); assertThrows(IllegalArgumentException.class, () -> service.history(req));
        req.setLimit(20); req.setBeforeId(0L); assertThrows(IllegalArgumentException.class, () -> service.history(req));
        req.setBeforeId(null);
        SecurityContextHolder.clearContext();
        assertThrows(IllegalStateException.class, () -> service.history(req));
    }

    private void append(Long app, Long grant, Long revision) {
        var row = new SupplyEtlGrantAuditDO(); row.setAppId(app); row.setGrantId(grant);
        row.setApiId(20L); row.setActorId(99L); row.setRevision(revision); row.setAction("CREATE"); row.setResult("SUCCESS");
        row.setReason("授权历史测试"); row.setBeforeJson("{}");
        row.setAfterJson(JsonUtils.toJsonString(Map.of("sourceType", "ADMIN_ETL_BATCH", "permissionUserId", 9007199254740993L,
                "foreverFlag", 1, "aggregateDisabled", false, "validFrom", LocalDateTime.of(2026, 10, 3, 10, 20))));
        assertEquals(1, mapper.append(row));
    }
    private SupplyEtlGrantAuditPageReqVO request(Long app, int limit) {
        var req = new SupplyEtlGrantAuditPageReqVO(); req.setAppId(app); req.setLimit(limit); return req;
    }
    protected void sql(String text) throws SQLException { try (var statement = control.createStatement()) { statement.execute(text); } }
}
