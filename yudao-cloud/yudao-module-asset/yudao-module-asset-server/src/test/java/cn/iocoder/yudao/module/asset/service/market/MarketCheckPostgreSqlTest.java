package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckDecisionReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckPageReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalWorkOrderMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckTicketRow;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 临时数据库中的真实来源迁移重放、联合范围、跨页排序、CAS竞争和代理事务回滚。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class MarketCheckPostgreSqlTest {
    private String controlUrl;
    private String database;
    private String jdbcUser;
    private Connection connection;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private MarketApprovalWorkOrderMapper mapper;

    @BeforeAll
    void createDisposableDatabaseAndReplayStructure() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        assertThat(controlUrl).doesNotContain(":5433/");
        jdbcUser = System.getenv().getOrDefault("ASSET_MARKET_TEST_JDBC_USER", "catalog_test");
        database = "approval_check_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, jdbcUser, ""); var statement = control.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, jdbcUser, "");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations/0225_asset_market-approval-work-order.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
        String migration = Files.readString(root.resolve("sql/postgresql/migrations/0225_asset_market-approval-work-order.sql"));
        sql(migration); sql(migration);
        // 本测试只为联合查询建立必要本地字段；全量基线和历史升级由独立交付验证覆盖。
        sql("CREATE TABLE dh_asset_data_asset(id bigint PRIMARY KEY,asset_name varchar(256),deleted smallint DEFAULT 0)");
        sql("CREATE TABLE dh_asset_market_listing(asset_id bigint PRIMARY KEY,display_name varchar(256),deleted smallint DEFAULT 0)");
        sql("CREATE TABLE dh_market_resource_apply(id bigint PRIMARY KEY,asset_id bigint,apply_no varchar(128),title varchar(256),apply_user_id bigint,status integer,create_time timestamp,deleted smallint DEFAULT 0,source_work_order_id bigint)");
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?socketTimeout=15", jdbcUser, "");
        transactions = new DataSourceTransactionManager(source);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("approval-isolated", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(MarketApprovalWorkOrderMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(MarketApprovalWorkOrderMapper.class);
    }

    @BeforeEach
    void fixtures() throws Exception {
        sql("TRUNCATE dh_market_approval_work_order,dh_market_resource_apply,dh_asset_market_listing,dh_asset_data_asset RESTART IDENTITY");
        sql("""
                INSERT INTO dh_market_approval_work_order(source_system,source_record_id,mart_source_id,mart_name,
                  applicant_key,applicant_name,apply_type,apply_time,source_status,detail_snapshot)
                VALUES ('QDATA','2','22','河道流量','99','来源申请人旧名',1,'2026-01-01','3','{}'),
                       ('QDATA','9','22','河道流量','99','来源申请人',1,'2026-03-01','2','{}'),
                       ('QDATA','21','23','雨量','88','来源机构',2,'2026-02-01','5','{}');
                INSERT INTO dh_asset_data_asset VALUES(10,'本地可审批',0),(11,'无权资产',0);
                INSERT INTO dh_asset_market_listing VALUES(10,'本地市场名称',0);
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_no,title,apply_user_id,status,create_time,deleted) VALUES
                  (9007199254740993,10,'MP001','本地申请',99,1,'2026-04-01',0),
                  (8,11,'MP002','越权申请',99,1,'2026-05-01',0);
                """);
    }

    @Test
    void unionFiltersNativeProviderScopeAndSeparatesApplicantNamespaces() {
        var req = new MarketCheckPageReqVO();
        assertThat(mapper.countTickets(req, List.of())).isEqualTo(3);
        assertThat(mapper.countTickets(req, List.of(10L))).isEqualTo(4);
        req.setMartName("本地市场");
        assertThat(mapper.selectTickets(req, List.of(10L), 0)).extracting(MarketCheckTicketRow::getId)
                .containsExactly("9007199254740993");
        req.setMartName(null); req.setApplicantKey("QDATA:99");
        assertThat(mapper.countTickets(req, List.of(10L))).isEqualTo(2);
        req.setApplicantKey("NATIVE:99");
        assertThat(mapper.countTickets(req, List.of(10L))).isEqualTo(1);
        req.setApplicantKey(null); req.setStatus("2");
        assertThat(mapper.countTickets(req, List.of(10L))).isEqualTo(2);
        req.setMartName("' OR 1=1 --");
        assertThat(mapper.countTickets(req, List.of(10L))).isZero();
        assertThat(mapper.selectApplicants(List.of(10L))).extracting(MarketCheckTicketRow::getApplicantKey)
                .containsExactly("NATIVE:99", "QDATA:88", "QDATA:99");
        assertThat(mapper.selectApplicants(List.of(10L)).getLast().getApplicantName()).isEqualTo("来源申请人");
    }

    @Test
    void numericIdentifierAndTimeSortingApplyBeforePagination() {
        var req = new MarketCheckPageReqVO(); req.setPageSize(1);
        assertThat(mapper.selectTickets(req, List.of(), 0)).extracting(MarketCheckTicketRow::getId).containsExactly("9");
        assertThat(mapper.selectTickets(req, List.of(), 1)).extracting(MarketCheckTicketRow::getId).containsExactly("21");
        req.setSortBy("displayId");
        assertThat(mapper.selectTickets(req, List.of(), 0)).extracting(MarketCheckTicketRow::getId).containsExactly("21");
        assertThat(mapper.selectTickets(req, List.of(), 1)).extracting(MarketCheckTicketRow::getId).containsExactly("9");
        req.setSortOrder("asc");
        assertThat(mapper.selectTickets(req, List.of(), 0)).extracting(MarketCheckTicketRow::getId).containsExactly("2");
        req.setSortBy("applyTime");
        assertThat(mapper.selectTickets(req, List.of(), 1)).extracting(MarketCheckTicketRow::getId).containsExactly("21");
        req.setSortOrder("desc");
        assertThat(mapper.selectTickets(req, List.of(), 0)).extracting(MarketCheckTicketRow::getId).containsExactly("9");
    }

    @Test
    void concurrentSourceDecisionHasExactlyOneWinnerAndPreservesSourceEvidence() throws Exception {
        var order = mapper.selectSource("9");
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                await(ready); return mapper.decideSource(order.getId(), 1, "3", "通过意见", 9L);
            }));
            var second = executor.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                await(ready); return mapper.decideSource(order.getId(), 1, "4", "驳回意见", 10L);
            }));
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        var current = mapper.selectSource("9");
        assertThat(current.getRevision()).isEqualTo(2);
        assertThat(current.getSourceStatus()).isEqualTo("2");
        assertThat(current.getApplicantKey()).isEqualTo("99");
        assertThat(current.getLocalStatus()).isIn("3", "4");
        assertThat(mapper.decideSource(order.getId(), 2, "3", "再次处理", 9L)).isZero();
    }

    @Test
    void auditFailureRollsBackSourceOpinionThroughRealSpringProxy() {
        var target = new MarketCheckServiceImpl();
        var guard = mock(MarketObjectGuard.class); when(guard.requireUserId()).thenReturn(9L);
        when(guard.hasPermission(MarketObjectGuard.PERM_APPLY_CHECK)).thenReturn(true);
        var original = mock(MarketApplyService.class); var audit = mock(MarketAuditRecorder.class);
        ReflectionTestUtils.setField(target, "workOrderMapper", mapper);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "applyService", original);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        doThrow(new IllegalStateException("审计写入失败")).when(audit)
                .record(anyString(), anyString(), anyLong(), anyInt(), anyInt(), any(), anyString());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var service = (MarketCheckService) proxy.getProxy();
        var request = new MarketCheckDecisionReqVO(); request.setId("9"); request.setOrigin("QDATA");
        request.setStatus("3"); request.setExpectedRevision(1);
        assertThatThrownBy(() -> service.check(request)).isInstanceOf(IllegalStateException.class);
        var order = mapper.selectSource("9");
        assertThat(order.getLocalStatus()).isNull(); assertThat(order.getRevision()).isEqualTo(1);
        assertThat(order.getSourceStatus()).isEqualTo("2");
        verifyNoInteractions(original);
    }

    @AfterAll
    void destroyOnlyOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null && controlUrl != null) {
            try (var control = DriverManager.getConnection(controlUrl, jdbcUser, ""); var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private void sql(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
