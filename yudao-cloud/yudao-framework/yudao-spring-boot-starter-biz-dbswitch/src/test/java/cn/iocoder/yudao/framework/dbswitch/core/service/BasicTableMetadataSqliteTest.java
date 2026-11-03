package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Actual SQLite 3.31.1 engine through the public facade; preserves its native catalog behavior. */
class BasicTableMetadataSqliteTest {
    @TempDir Path directory;

    @Test void nativeColumnTypesAndKeyOrderAreReadWithoutNetworkTimeoutSupport() throws Exception {
        String url = create("CREATE TABLE rows(a TEXT NOT NULL,z INTEGER NOT NULL,payload BLOB,amount DECIMAL(12,2),PRIMARY KEY(z,a)) WITHOUT ROWID");
        var opens = new AtomicInteger(); var closes = new AtomicInteger(); var networkCalls = new AtomicInteger();
        var owned = new AtomicReference<Connection>();
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.SQLITE3, DbSwitchProductRegistry.discover(), (jdbc, user, password) -> {
            opens.incrementAndGet(); var connection = DriverManager.getConnection(jdbc); owned.set(connection);
            return guard(connection, false, closes, networkCalls);
        });
        var result = service.queryTableColumnsAndKeys(url, null, null, "main", "rows");
        assertEquals(List.of("a", "z", "payload", "amount"), result.columns().stream().map(column -> column.getFieldName()).toList());
        assertEquals(List.of("z", "a"), result.primaryKeys());
        assertEquals("BLOB", result.columns().get(2).getMetadataEvidence().nativeType().value());
        assertEquals("DECIMAL(12,2)", result.columns().get(3).getMetadataEvidence().nativeType().value());
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertEquals(0, networkCalls.get()); assertTrue(owned.get().isClosed());
    }

    @Test void aRealKeylessTableDoesNotAcquireInventedKeys() throws Exception {
        String url = create("CREATE TABLE rows(value TEXT)");
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.SQLITE3, DbSwitchProductRegistry.discover(),
                (jdbc, user, password) -> DriverManager.getConnection(jdbc));
        var result = service.queryTableColumnsAndKeys(url, null, null, "main", "rows");
        assertEquals("value", result.columns().getFirst().getFieldName()); assertTrue(result.primaryKeys().isEmpty());
    }

    @Test void nativePrimaryKeyFailureCannotReturnPartialColumnSuccessOrLeakTheOwnedConnection() throws Exception {
        String url = create("CREATE TABLE rows(id INTEGER PRIMARY KEY)");
        var closes = new AtomicInteger(); var networkCalls = new AtomicInteger(); var owned = new AtomicReference<Connection>();
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.SQLITE3, DbSwitchProductRegistry.discover(), (jdbc, user, password) -> {
            var connection = DriverManager.getConnection(jdbc); owned.set(connection); return guard(connection, true, closes, networkCalls);
        });
        var failure = assertThrows(RuntimeException.class, () -> service.queryTableColumnsAndKeys(url, null, null, "main", "rows"));
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(1, closes.get()); assertEquals(0, networkCalls.get()); assertTrue(owned.get().isClosed());
    }

    private String create(String ddl) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("basic68.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) { statement.execute(ddl); }
        return url;
    }

    private static Connection guard(Connection physical, boolean failKeys, AtomicInteger closes, AtomicInteger networkCalls) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("setNetworkTimeout") || method.getName().equals("getNetworkTimeout")) {
                networkCalls.incrementAndGet(); throw new SQLFeatureNotSupportedException("network deadline unavailable");
            }
            if (method.getName().equals("close")) closes.incrementAndGet();
            if (failKeys && method.getName().equals("prepareStatement") && ((String)args[0]).contains("WHERE pk > 0 ORDER BY pk"))
                throw new SQLException("native PK query failed");
            try { return method.invoke(physical, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
}
