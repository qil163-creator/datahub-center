package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCommentSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.governance.api.quality.QualityWorkOrderApi;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_COMMENT_REQUEST_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用真实事务和两条独立连接验证跨请求锁；治理 RPC 必须发生在提交之后。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketCommentIdempotencyPostgresTest extends SharingPostgresSupport {

    private MarketCommentServiceImpl service;
    private MarketAuditRecorder audit;
    private QualityWorkOrderApi workOrders;

    @BeforeEach
    void prepareCommentService() throws Exception {
        sql("""
                CREATE TABLE dh_market_resource_comment (
                  id bigserial PRIMARY KEY, asset_id bigint, apply_id bigint, comment_str varchar(500),
                  level int, reply_str varchar, reply_user_id bigint, reply_time timestamp,
                  comment_kind int, problem_type varchar, impact_scope varchar, found_time timestamp,
                  work_order_id bigint, work_order_status varchar, idempotent_key varchar(64),
                  creator varchar, updater varchar, create_time timestamp, update_time timestamp,
                  deleted smallint NOT NULL DEFAULT 0);
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", jdbcUser, "");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("market-comment-operation", new SpringManagedTransactionFactory(), source));
        var global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(MarketResourceCommentMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        service = new MarketCommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", session.getMapper(MarketResourceCommentMapper.class));
        ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(service, "assetMapper", mock(DataAssetMapper.class));
        audit = mock(MarketAuditRecorder.class);
        ReflectionTestUtils.setField(service, "auditRecorder", audit);
        workOrders = mock(QualityWorkOrderApi.class);
        when(workOrders.createFromMarket(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("RPC after local commit").isFalse();
            return CommonResult.success(null);
        });
        ReflectionTestUtils.setField(service, "qualityWorkOrderApi", workOrders);
    }

    @Test
    void overlappingRetriesWaitForTheSameTransactionAndCreateOneReport() throws Exception {
        String operation = "4eb76d2e-7b46-43f8-9912-201949aa6e0e";
        String key = DigestUtil.sha256Hex("market-check/v1:7:" + operation);
        try (var blocker = open(); var workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, key);
                statement.execute();
            }
            var first = workers.submit(() -> submit(7L, request(operation, "口径错误")));
            var retry = workers.submit(() -> submit(7L, request(operation, "口径错误")));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'") < 2) {
                    assertThat(System.nanoTime()).as("both requests wait on actual PostgreSQL advisory lock").isLessThan(deadline);
                    Thread.sleep(10);
                }
                assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment")).isZero();
            } finally {
                blocker.commit();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(retry.get(10, TimeUnit.SECONDS));
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment")).isEqualTo(1);
        verify(workOrders, times(1)).createFromMarket(any());
        verify(audit, times(1)).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theSameOperationCannotSilentlyReturnADifferentPayload() {
        submit(7L, request("same-key", "原始问题"));
        var error = assertThrows(ServiceException.class, () -> submit(7L, request("same-key", "其他问题")));
        assertThat(error.getCode()).isEqualTo(MARKET_COMMENT_REQUEST_CONFLICT.getCode());
        verify(workOrders, times(1)).createFromMarket(any());
    }

    @Test
    void twoUsersCanUseTheSameClientOperationWithoutSharingAReport() throws Exception {
        Long first = submit(7L, request("same-client-key", "同一个问题"));
        Long second = submit(8L, request("same-client-key", "同一个问题"));
        assertThat(first).isNotEqualTo(second);
        assertThat(scalar("SELECT count(DISTINCT idempotent_key) FROM dh_market_resource_comment")).isEqualTo(2);
        assertThat(scalar("SELECT count(DISTINCT creator) FROM dh_market_resource_comment")).isEqualTo(2);
    }

    @Test
    void rollbackReleasesTheRequestLockAndAllowsAStableRetry() throws Exception {
        doThrow(new IllegalStateException("audit unavailable")).doNothing().when(audit)
                .record(any(), any(), any(), any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> submit(7L, request("rollback-key", "同一问题")));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment")).isZero();
        verifyNoInteractions(workOrders);
        assertThat(submit(7L, request("rollback-key", "同一问题"))).isPositive();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_comment")).isEqualTo(1);
        verify(workOrders, times(1)).createFromMarket(any());
    }

    private Long submit(Long userId, MarketCommentSaveReqVO request) {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(userId);
            return service.createComment(request);
        }
    }

    private MarketCommentSaveReqVO request(String key, String content) {
        MarketCommentSaveReqVO req = new MarketCommentSaveReqVO();
        req.setAssetId(9223372036854775807L);
        req.setCommentKind(2);
        req.setCommentStr(content);
        req.setProblemType("口径");
        req.setIdempotentKey(key);
        return req;
    }
}
