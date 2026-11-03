package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实际 PostgreSQL 事务快照、时钟、跨连接撤权与租户约束；不依赖 Docker/H2 或猜测等待时间。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketQuerySnapshotPostgresTest extends SharingPostgresSupport {
    private MarketAccessServiceImpl service;
    private MarketResourceApplyMapper mapper;
    private DataSourceTransactionManager transactions;
    private DataAssetDO asset;
    private DatabaseApi database;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactions = new DataSourceTransactionManager(datasource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("query-snapshot", new SpringManagedTransactionFactory(), datasource));
        config.addMapper(MarketResourceApplyMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config))
                .getMapper(MarketResourceApplyMapper.class);
        mapper.insert(MarketResourceApplyDO.builder().id(7L).assetId(5L).applyUserId(9L)
                .status(2).ifAuth(1).paused(0).revision(1).sourceRevision(2L).snapshot("""
                    {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                     "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                    """).build());
        asset = DataAssetDO.builder().id(5L).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .status(DataAssetDO.STATUS_PUBLISHED).datasourceId(4L).schemaName("public").tableName("citizen")
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L).build();
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(asset);
        service = new MarketAccessServiceImpl();
        ReflectionTestUtils.setField(service, "metadataIdentityGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard.class));
        ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        database = mock(DatabaseApi.class);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "assetMonitorService", mock(AssetMonitorService.class));
        TenantContextHolder.clear();
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
    }

    @AfterEach
    void clearContext() {
        if (security != null) security.close();
        TenantContextHolder.clear();
    }

    @Test
    void unchangedScopeIsImmutableAndCanBeReleased() {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        assertThat(snapshot.fieldScope().getColumns()).containsExactly("Id");
        assertThatThrownBy(() -> snapshot.fieldScope().getColumns().add("secret"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.evaluatedAt()).isNotNull();
        assertThatCode(() -> service.assertStillCurrent(snapshot)).doesNotThrowAnyException();
    }

    @Test
    void anotherConnectionCanRevokeWhileQueryRunsAndResultIsDiscarded() throws Exception {
        var queryStarted = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var revoke = worker.submit(() -> {
                if (!queryStarted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Query not started");
                try (var c = open(); var statement = c.createStatement()) {
                    statement.execute("UPDATE dh_market_resource_apply SET status=6,if_auth=0,source_revision=3 WHERE id=7");
                }
                return true;
            });
            when(database.previewTable(any())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                queryStarted.countDown();
                assertThat(revoke.get(5, TimeUnit.SECONDS)).isTrue();
                return result();
            });
            try {
                assertChanged(() -> service.previewAuthorizedAsset(5L, 20));
            } finally {
                queryStarted.countDown();
            }
            assertThat(revoke.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(mapper.selectById(7L).getFirstUsedTime()).isNull();
        verify(database).previewTable(any());
    }

    @Test
    void finalReadDoesNotReuseCallersOldRepeatableReadTransaction() {
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(mapper.selectForAccess( 7, 9).getStatus()).isEqualTo(2);
            var snapshot = service.requireQuerySnapshot(9L, 7L);
            try {
                sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0,source_revision=3 WHERE id=7");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            assertThat(mapper.selectForAccess( 7, 9).getStatus()).isEqualTo(2);
            assertChanged(() -> service.assertStillCurrent(snapshot));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    }

    @Test
    void suspensionAndResumeWithHigherRevisionRejectsOldResponse() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET paused=1,source_revision=3 WHERE id=7");
        sql("UPDATE dh_market_resource_apply SET paused=0,source_revision=4 WHERE id=7");
        assertChanged(() -> service.assertStillCurrent(snapshot));
        assertThatCode(() -> service.requireQuerySnapshot(9L, 7L)).doesNotThrowAnyException();
    }

    @Test
    void expirationUsesDatabaseClockWithoutWaitingForExpiryJob() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET apply_end_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai' - interval '1 microsecond' WHERE id=7");
        assertThat(mapper.selectById(7L).getStatus()).isEqualTo(2);
        assertChanged(() -> service.assertStillCurrent(snapshot));
        assertThat(service.findEffectiveApply(9L, 5L)).isNull();
    }

    @Test
    void guardQueryHasHalfOpenEndAndExcludesPausedApplications() throws Exception {
        var boundary = LocalDateTime.of(2040, 1, 1, 0, 0);
        sql("UPDATE dh_market_resource_apply SET apply_start_time='2039-01-01',apply_end_time='2040-01-01' WHERE id=7");
        assertThat(mapper.selectEffective(9L, 5L, boundary.minusSeconds(1))).isNotNull();
        assertThat(mapper.selectEffective(9L, 5L, boundary)).isNull();
        sql("UPDATE dh_market_resource_apply SET paused=1 WHERE id=7");
        assertThat(mapper.selectEffective(9L, 5L, boundary.minusSeconds(1))).isNull();
    }

    @Test
    void deletedRecordOrMissingRevisionCannotReleaseOldResponse() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET source_revision=NULL WHERE id=7");
        assertChanged(() -> service.assertStillCurrent(snapshot));
        sql("UPDATE dh_market_resource_apply SET source_revision=2,deleted=1 WHERE id=7");
        assertChanged(() -> service.assertStillCurrent(snapshot));
    }

    @Test
    void publishedBindingChangeOrOfflineAssetRejectsOldResponse() {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        asset.setSchemaName("private");
        assertChanged(() -> service.assertStillCurrent(snapshot));
        asset.setSchemaName("public");
        asset.setStatus(DataAssetDO.STATUS_OFFLINE);
        assertChanged(() -> service.assertStillCurrent(snapshot));
        asset.setStatus(DataAssetDO.STATUS_PUBLISHED);
        asset.setAssetType(DataAssetDO.TYPE_API);
        assertChanged(() -> service.assertStillCurrent(snapshot));
    }

    @Test
    void changedPersistentEvidenceRejectsEvenWithoutAnUpdatedRevision() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET snapshot=replace(snapshot, '\"Id\"', '\"other\"') WHERE id=7");
        assertChanged(() -> service.assertStillCurrent(snapshot));
    }

    @Test
    void callerChangesNeverReuseSnapshots() throws Exception {
        TenantContextHolder.clear();
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        service.assertStillCurrent(snapshot);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
        assertThatThrownBy(() -> service.assertStillCurrent(snapshot)).isInstanceOf(ServiceException.class);
    }

    private static void assertChanged(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
    }

    private static CommonResult<DatabasePreviewRespDTO> result() {
        var result = new DatabasePreviewRespDTO();
        result.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint")));
        result.setRows(List.of(Map.of("Id", 1)));
        return CommonResult.success(result);
    }
}
