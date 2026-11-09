package cn.iocoder.yudao.framework.dbswitch.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTimeoutConnectionTest {

    /** 记录 setQueryTimeout 调用的语句替身 */
    private static <T extends Statement> T statement(Class<T> type, List<Integer> timeouts) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("setQueryTimeout")) {
                timeouts.add((Integer) args[0]);
            }
            return null;
        }));
    }

    private static Connection connection(List<Integer> timeouts, List<String> calls) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "createStatement" -> statement(Statement.class, timeouts);
                        case "prepareStatement" -> statement(PreparedStatement.class, timeouts);
                        case "prepareCall" -> statement(CallableStatement.class, timeouts);
                        case "getAutoCommit" -> true;
                        case "commit" -> throw new SQLException("commit refused", "08006");
                        default -> null;
                    };
                });
    }

    @Test
    void everyCreatedStatementGetsTheTimeout() throws Exception {
        List<Integer> timeouts = new ArrayList<>();
        Connection wrapped = QueryTimeoutConnection.wrap(connection(timeouts, new ArrayList<>()), 7);

        wrapped.createStatement();
        wrapped.prepareStatement("select 1");
        wrapped.prepareStatement("select ?", Statement.RETURN_GENERATED_KEYS);
        wrapped.prepareCall("{call p()}");

        assertEquals(List.of(7, 7, 7, 7), timeouts);
    }

    @Test
    void otherCallsAreDelegatedAndDriverExceptionsAreNotWrapped() throws Exception {
        List<String> calls = new ArrayList<>();
        Connection wrapped = QueryTimeoutConnection.wrap(connection(new ArrayList<>(), calls), 5);

        assertTrue(wrapped.getAutoCommit());
        SQLException failure = assertThrows(SQLException.class, wrapped::commit);
        assertEquals("08006", failure.getSQLState());
        assertEquals(List.of("getAutoCommit", "commit"), calls);
        assertEquals(wrapped, wrapped);
        assertNotEquals(wrapped, QueryTimeoutConnection.wrap(connection(new ArrayList<>(), calls), 5));
    }

    @Test
    void nonPositiveTimeoutReturnsTheOriginalConnection() {
        Connection original = connection(new ArrayList<>(), new ArrayList<>());
        assertSame(original, QueryTimeoutConnection.wrap(original, 0));
        assertSame(original, QueryTimeoutConnection.wrap(original, -1));
    }

    @Test
    void recognisesStandardAndSqlStateTimeouts() {
        assertTrue(QueryTimeoutConnection.isQueryTimeout(new RuntimeException("查询失败", new SQLTimeoutException("timeout"))));
        assertTrue(QueryTimeoutConnection.isQueryTimeout(
                new IllegalStateException(new RuntimeException(new SQLException("canceling statement", "57014")))));
        assertFalse(QueryTimeoutConnection.isQueryTimeout(new RuntimeException(new SQLException("syntax", "42601"))));
        assertFalse(QueryTimeoutConnection.isQueryTimeout(new RuntimeException("no cause")));
    }
}
