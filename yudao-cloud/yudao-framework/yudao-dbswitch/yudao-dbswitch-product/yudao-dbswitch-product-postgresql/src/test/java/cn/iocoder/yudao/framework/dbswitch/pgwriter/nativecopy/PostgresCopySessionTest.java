package cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgresCopySessionTest {
    @Test void nativePreparationDoesNotStartTheProtocolAndOpenerCannotBeReused() throws Exception {
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        var pg = mock(PGConnection.class); var manager = mock(CopyManager.class); var operation = mock(CopyIn.class);
        when(((DriverNativeAccess.ConnectionAccess) connection).unwrapNative("org.postgresql.PGConnection")).thenReturn(pg);
        when(pg.getCopyAPI()).thenReturn(manager); when(manager.copyIn(anyString())).thenReturn(operation);
        var prepared = PostgresCopySession.prepare(connection);
        verify(manager, never()).copyIn(anyString());
        assertNotNull(prepared.start("COPY fixture FROM STDIN BINARY"));
        assertThrows(IllegalStateException.class, () -> prepared.start("COPY fixture FROM STDIN BINARY"));
        verify(manager).copyIn(anyString());
    }

    @Test void jdbcEntryUsesRuntimeNativeAccessAndRestoresTcclEvenWhenDriverThrows() throws Exception {
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        var pg = mock(PGConnection.class); var manager = mock(CopyManager.class); var operation = mock(CopyIn.class);
        when(((DriverNativeAccess.ConnectionAccess) connection).unwrapNative("org.postgresql.PGConnection")).thenReturn(pg);
        when(pg.getCopyAPI()).thenReturn(manager);
        when(manager.copyIn(anyString())).thenAnswer(call -> {
            assertSame(pg.getClass().getClassLoader(), Thread.currentThread().getContextClassLoader()); return operation;
        });
        var previous = Thread.currentThread().getContextClassLoader();
        var sentinel = new ClassLoader(previous) { };
        try {
            Thread.currentThread().setContextClassLoader(sentinel);
            CopySession session = PostgresCopySession.open(connection, "COPY fixture FROM STDIN BINARY");
            assertSame(sentinel, Thread.currentThread().getContextClassLoader());
            var driverFailure = new SQLException("private fixture failure");
            doAnswer(call -> {
                assertSame(pg.getClass().getClassLoader(), Thread.currentThread().getContextClassLoader()); throw driverFailure;
            }).when(operation).writeToCopy(any(byte[].class), anyInt(), anyInt());
            assertSame(driverFailure, assertThrows(SQLException.class, () -> session.writeToCopy(new byte[]{1}, 0, 1)));
            assertSame(sentinel, Thread.currentThread().getContextClassLoader());
            verify(connection, never()).unwrap(PGConnection.class);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
