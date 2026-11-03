package cn.iocoder.yudao.framework.dbswitch.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JdbcReadDeadlineCompatibilityTest {
    @Test void preJdbc41BinaryGetsCheckedRefusalBeforeSchedulingOrBorrowedConnectionMutation() {
        var closed = new AtomicBoolean();
        var aborted = new AtomicBoolean();
        var linkage = new AbstractMethodError("legacy JDBC binary");
        Connection connection = connection((method, args) -> {
            return switch (method) {
                case "setNetworkTimeout" -> throw linkage;
                case "close" -> { closed.set(true); yield null; }
                case "abort" -> { aborted.set(true); yield null; }
                default -> throw new AssertionError("Unexpected connection call: " + method);
            };
        });
        var refusal = assertThrows(SQLFeatureNotSupportedException.class,
                () -> new JdbcReadDeadline(connection, 1000));
        assertEquals("0A000", refusal.getSQLState());
        assertSame(linkage, refusal.getCause());
        assertFalse(closed.get());
        assertFalse(aborted.get());
    }

    @Test void explicitDriverRefusalIsPreserved() {
        var original = new SQLFeatureNotSupportedException("network timeout unsupported", "0A000");
        Connection connection = connection((method, args) -> {
            if (method.equals("setNetworkTimeout")) throw original;
            throw new AssertionError("Unexpected connection call: " + method);
        });
        assertSame(original, assertThrows(SQLException.class, () -> new JdbcReadDeadline(connection, 1000)));
    }

    @Test void successfulDeadlineDoesNotOwnOrReconfigureBorrowedTransaction() throws Exception {
        var configured = new AtomicBoolean();
        Connection connection = connection((method, args) -> {
            if (method.equals("setNetworkTimeout")) {
                assertEquals(60_000, args[1]);
                configured.set(true);
                return null;
            }
            throw new AssertionError("Unexpected connection call: " + method);
        });
        try (var deadline = new JdbcReadDeadline(connection, 60_000)) {
            deadline.check();
            assertTrue(configured.get());
        }
    }

    private interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    private static Connection connection(Call call) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> call.invoke(method.getName(), args));
    }
}
