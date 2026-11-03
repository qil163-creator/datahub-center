package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderPageReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderIssueMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 PG / MyBatis 分页插件，真实 Guard；仅权限、用户 API 为替身。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Timeout(30)
class QualityWorkOrderStatisticsPostgreSqlTest {
    private final String schema = "quality_stats_" + UUID.randomUUID().toString().replace("-", "");
    private final String table = "dh_governance_quality_work_order";
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private QualityWorkOrderMapper mapper;
    private QualityWorkOrderGuard guard;
    private QualityWorkOrderServiceImpl service;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private boolean created;
    private boolean assign;
    private boolean coordinate;
    private Long userId = 9L;
    private Long department = 90L;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Only an explicit loopback test database is allowed");
        }
        var adminSource = new DriverManagerDataSource(url, System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE SCHEMA " + schema); created = true;
        var source = new DriverManagerDataSource(url, System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        var options = new Properties();
        options.setProperty("currentSchema", schema);
        options.setProperty("connectTimeout", "5"); options.setProperty("socketTimeout", "15");
        options.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        source.setConnectionProperties(options);
        db = new JdbcTemplate(source);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(db, schema, List.of(table));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setDefaultStatementTimeout(10);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptors);
        config.setEnvironment(new Environment("owner-statistics", new SpringManagedTransactionFactory(), source));
        config.addMapper(QualityWorkOrderMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(QualityWorkOrderMapper.class);
        guard = spy(new QualityWorkOrderGuard());
        doAnswer(call -> userId).when(guard).requireUserId();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        ReflectionTestUtils.setField(guard, "adminUserApi", users);
        when(permissions.hasAnyPermissions(anyLong(), anyString())).thenAnswer(call -> CommonResult.success(
                QualityWorkOrderGuard.PERM_ASSIGN.equals(call.getArgument(1)) ? assign
                        : QualityWorkOrderGuard.PERM_COORDINATE.equals(call.getArgument(1)) && coordinate));
        when(users.getUser(anyLong())).thenAnswer(call -> {
            var user = new AdminUserRespDTO(); user.setId(userId); user.setDeptId(department);
            return CommonResult.success(user);
        });
        when(users.getUserList(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return CommonResult.success(ids.stream().map(id -> {
                var user = new AdminUserRespDTO(); user.setId(id); user.setNickname("用户" + id); return user;
            }).toList());
        });
        service = new QualityWorkOrderServiceImpl();
        ReflectionTestUtils.setField(service, "workOrderMapper", mapper);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        ReflectionTestUtils.setField(service, "workOrderIssueMapper", mock(QualityWorkOrderIssueMapper.class));
        TenantContextHolder.clear();
        row(1, 9L, null, null, 99L, "ASSIGNED");
        row(2, 20L, 9L, null, 20L, "ASSIGNED");
        row(3, 21L, null, 9L, 20L, "ASSIGNED");
        row(4, 22L, null, null, 90L, "ASSIGNED");
        row(5, 23L, null, null, 91L, "ASSIGNED");
        row(6, null, 9L, null, 90L, "UNASSIGNED");
        row(7, 9L, null, null, 90L, "UNASSIGNED");
        row(8, 24L, null, null, 90L, "ASSIGNED");
        row(9, 25L, null, null, null, "ASSIGNED");
        row(10, 9L, null, null, 90L, "ASSIGNED");
        row(11, 9L, null, null, 90L, "ASSIGNED");
        row(12, 9L, null, null, 90L, "ASSIGNED");
        row(13, 9L, null, null, 90L, "ASSIGNED");
        db.update("UPDATE " + table + " SET deleted=1 WHERE id=10");
        db.update("UPDATE " + table + " SET deleted=1 WHERE id=11");
        db.update("UPDATE " + table + " SET create_time=now()-interval '100 days' WHERE id=12");
        db.update("UPDATE " + table + " SET create_time=now()+interval '1 day' WHERE id=13");
        db.update("UPDATE " + table + " SET order_status='PROCESSED',closed_at=now(),close_type='EXPLAINED' WHERE id=2");
    }

    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        if (created) {
            if (!schema.matches("quality_stats_[a-f0-9]{32}")) throw new IllegalStateException("Invalid owned schema");
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @ParameterizedTest @CsvSource({"false,false,5", "true,false,9", "false,true,7"})
    void statisticsAndEveryPageHaveExactlyTheDetailGuardsScope(boolean canAssign, boolean canCoordinate, int count) {
        assign = canAssign; coordinate = canCoordinate;
        var stats = service.getWorkOrderStatistics(7);
        assertThat(stats.getTotalCount()).isEqualTo(count);
        assertThat(stats.getOwnerSummary().stream().mapToInt(item -> item.count()).sum()).isEqualTo(count);
        assertThat(stats.getStatusSummary().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(count);
        assertThat(stats.getTrend().stream().mapToInt(item -> item.createdCount()).sum()).isEqualTo(count);
        assertThat(stats.getTrend().stream().mapToInt(item -> item.processedCount()).sum()).isEqualTo(1);
        assertThat(stats.getCloseExplained()).isEqualTo(1);
        assertThat(stats.getCloseVerifiedPass()).isZero();
        var visible = new HashSet<Long>();
        for (int page = 1; page <= (count + 1) / 2; page++) {
            var req = new QualityWorkOrderPageReqVO(); req.setPageNo(page); req.setPageSize(2);
            req.setCreateTime(new LocalDateTime[]{stats.getAsOf().toLocalDate().minusDays(6).atStartOfDay(), stats.getAsOf()});
            var result = service.getWorkOrderPage(req);
            assertThat(result.getTotal()).isEqualTo(count);
            result.getList().forEach(row -> visible.add(row.getId()));
        }
        assertThat(visible).hasSize(count);
        for (long id = 1; id <= 9; id++) {
            var row = mapper.selectById(id);
            if (visible.contains(id)) assertThatCode(() -> guard.assertVisible(row, userId)).doesNotThrowAnyException();
            else assertThatThrownBy(() -> guard.assertVisible(row, userId))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        if (!assign) assertThat(stats.getOwnerSummary()).noneMatch(item -> item.ownerUserId() == null);
    }

    @Test void hiddenOwnersAreNotFetchedOrReturned() {
        var stats = service.getWorkOrderStatistics(7);
        assertThat(stats.getOwnerSummary()).extracting(item -> item.ownerUserId()).containsExactly(9L, 20L, 21L, 22L, 24L);
        verify(users).getUserList(Set.of(9L, 20L, 21L, 22L, 24L));
        assertThat(stats.getOwnerSummary()).extracting(item -> item.ownerUserName()).doesNotContain("用户23", "用户25");
    }

    @Test void missingDepartmentDoesNotMatchNullDepartment() {
        department = null;
        assertThat(service.getWorkOrderStatistics(7).getTotalCount()).isEqualTo(3);
    }

    @Test void noVisibleDataHasAnExplicitEmptyResultAndDoesNotFetchNames() {
        userId = 900L; department = 900L;
        var stats = service.getWorkOrderStatistics(7);
        assertThat(stats.getSampleStatus()).isEqualTo("NO_SAMPLE");
        assertThat(stats.getTotalCount()).isZero();
        assertThat(stats.getOwnerSummary()).isEmpty();
        assertThat(stats.getStatusSummary()).isEmpty();
        assertThat(stats.getTrend()).hasSize(7).allMatch(item -> item.createdCount() == 0 && item.processedCount() == 0);
        verify(users, never()).getUserList(any());
    }

    @Test void privilegedUsersSeeAllActiveOrdersWithoutTenantContext() {
        assign = true;
        TenantContextHolder.clear();
        assertThat(service.getWorkOrderStatistics(7).getTotalCount()).isEqualTo(9);
        db.update("UPDATE " + table + " SET deleted=1 WHERE deleted=0");
        assertThat(service.getWorkOrderStatistics(7).getTotalCount()).isZero();
    }

    @Test void permissionFailuresDoNotReturnAnAuthorizedEmptyOrFullResult() {
        when(permissions.hasAnyPermissions(9L, QualityWorkOrderGuard.PERM_ASSIGN)).thenThrow(new IllegalStateException("fixture unavailable"));
        assertThatThrownBy(() -> service.getWorkOrderStatistics(7)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.getWorkOrderPage(new QualityWorkOrderPageReqVO())).isInstanceOf(IllegalStateException.class);
        verify(users, never()).getUserList(any());
    }

    @Test void transferRemovesThePreviousOwnersVisibilityOnTheNextRead() {
        assertThat(service.getWorkOrderStatistics(7).getTotalCount()).isEqualTo(5);
        db.update("UPDATE " + table + " SET owner_user_id=999 WHERE id=1");
        assertThat(service.getWorkOrderStatistics(7).getTotalCount()).isEqualTo(4);
    }

    @Test void clientFilterCanOnlyNarrowTheAuthorizedPage() {
        var req = new QualityWorkOrderPageReqVO(); req.setOwnerUserId(23L); req.setHandlingDeptId(91L);
        var result = service.getWorkOrderPage(req);
        assertThat(result.getTotal()).isZero(); assertThat(result.getList()).isEmpty();
    }

    private void row(long id, Long owner, Long creator, Long confirmer, Long dept, String responsibility) {
        db.update("INSERT INTO " + table + " (id,order_name,owner_user_id,creator_user_id,confirmer_user_id,handling_dept_id,responsibility_status,order_status,create_time) VALUES(?,?,?,?,?,?,?,'PENDING',?)",
                id, "fixture " + id, owner, creator, confirmer, dept, responsibility, LocalDateTime.now().minusHours(1));
    }
}
