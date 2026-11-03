package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlCompatibleStreamLoadRefusalTest {
    @Test
    void defaultStreamLoadRefusesBeforeMetadataQuery() {
        var calls = new AtomicInteger();
        DataSource source = rejecting(calls);
        var writer = new Observed("Doris", source);
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.prepareWrite("app", "items", List.of("id")));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("Doris"));
        assertEquals(0, calls.get());
    }

    @Test
    void jdbcInsertStillPreparesWhenStreamLoadIsOff() {
        var calls = new AtomicInteger();
        DataSource source = rejecting(calls);
        var writer = new Observed("Doris", source);
        var defaults = DbSwitchOptions.defaults();
        writer.configure(new DbSwitchOptions(defaults.maxCacheBytes(), defaults.pipelineWriteEnabled(), false,
                defaults.queryTimeoutSeconds(), defaults.poolMinimumIdle(), defaults.poolIdleTimeoutMs(),
                defaults.poolValidationTimeoutMs(), defaults.poolLeakDetectionThresholdMs(), defaults.diffSort()));
        assertThrows(RuntimeException.class, () -> writer.prepareWrite("app", "items", List.of("id")));
        assertTrue(calls.get() > 0);
    }

    private static DataSource rejecting(AtomicInteger calls) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException(method.getName());
                });
    }

    private static final class Observed extends MysqlCompatibleOlapWriterImpl {
        private final String product;

        Observed(String product, DataSource source) {
            super(source);
            this.product = product;
            columnType = Map.of("id", java.sql.Types.BIGINT);
        }

        @Override
        protected String getDatabaseProductName() {
            return product;
        }
    }
}
