package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriverVendorLogPolicyTest {
    @Test void knownBridgeCannotPublishMessageParametersOrThrowableAndOtherLoggersAreUnaffected() {
        Logger unsafe = Logger.getLogger("com.gitee.jdbc.mongodb.wrappers.WrappedMongoClient");
        Logger ordinary = Logger.getLogger("dbswitch.test.ordinary.driver.log");
        var seen = new AtomicInteger();
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { seen.incrementAndGet(); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var oldUnsafeLevel = unsafe.getLevel(); var oldOrdinaryLevel = ordinary.getLevel();
        boolean oldUnsafeParents = unsafe.getUseParentHandlers(), oldOrdinaryParents = ordinary.getUseParentHandlers();
        try {
            unsafe.setLevel(Level.ALL); ordinary.setLevel(Level.ALL);
            unsafe.setUseParentHandlers(false); ordinary.setUseParentHandlers(false);
            unsafe.addHandler(handler); ordinary.addHandler(handler);
            DriverVendorLogPolicy.beforeInitialization(ProductTypeEnum.MONGODB, "com.gitee.jdbc.mongodb.JdbcDriver");
            var record = new LogRecord(Level.SEVERE, "synthetic unsafe URI must never leave the bridge logger");
            record.setParameters(new Object[]{"synthetic parameter"}); record.setThrown(new IllegalArgumentException("synthetic throwable"));
            unsafe.log(record); assertEquals(0, seen.get());
            ordinary.info("ordinary diagnostic"); assertEquals(1, seen.get());
            // Re-check before every connection if application logging reconfiguration has replaced a filter.
            unsafe.setFilter(null);
            DriverVendorLogPolicy.beforeInitialization(ProductTypeEnum.MONGODB, "com.gitee.jdbc.mongodb.JdbcDriver");
            unsafe.log(record); assertEquals(1, seen.get());
        } finally {
            unsafe.removeHandler(handler); ordinary.removeHandler(handler);
            unsafe.setLevel(oldUnsafeLevel); ordinary.setLevel(oldOrdinaryLevel);
            unsafe.setUseParentHandlers(oldUnsafeParents); ordinary.setUseParentHandlers(oldOrdinaryParents);
            // Intentionally do not restore unsafe output: the production policy is process-long.
        }
    }
}
