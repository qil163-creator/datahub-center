package cn.iocoder.yudao.module.asset.service.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 事务代理验证：跨服务身份读取不能位于申请的本地 RR 或调用方事务内。 */
@EnabledIfEnvironmentVariable(named = "METADATA_WRITE_TEST_JDBC_URL", matches = ".+")
class AssetMetadataIdentityTransactionPostgresTest {

    @Test
    void metadataRpcRunsOutsideBothLocalSnapshotAndOuterTransaction() {
        String url = System.getenv("METADATA_WRITE_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+");
        var datasource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_USER", ""),
                System.getenv().getOrDefault("METADATA_WRITE_TEST_JDBC_PASSWORD", ""));
        var transactions = new DataSourceTransactionManager(datasource);
        var fixture = new AssetIdentityFixture();
        var factory = new ProxyFactory(fixture.identities);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(fixture.market, "metadataIdentityGuard", factory.getProxy());
        ReflectionTestUtils.setField(fixture.market, "transactionManager", transactions);
        var calls = new AtomicInteger();
        when(fixture.metadata.getTable(10L)).thenAnswer(call -> {
            assertNoTransaction(calls); return CommonResult.success(fixture.table);
        });
        when(fixture.metadata.getColumnListBySnapshot(11L)).thenAnswer(call -> {
            assertNoTransaction(calls); return CommonResult.success(List.of(AssetIdentityFixture.column(100L, "Id")));
        });
        when(fixture.metadata.getColumnList(10L)).thenAnswer(call -> {
            assertNoTransaction(calls); return CommonResult.success(List.of(fixture.column));
        });
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            outer.executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var resource = TransactionSynchronizationManager.getResource(datasource);
                assertThat(fixture.market.requireQuerySnapshot(9L, 7L).assetId()).isEqualTo(5L);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.getResource(datasource)).isSameAs(resource);
                assertThat(calls).hasValue(3);
            });
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static void assertNoTransaction(AtomicInteger calls) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        calls.incrementAndGet();
    }
}
