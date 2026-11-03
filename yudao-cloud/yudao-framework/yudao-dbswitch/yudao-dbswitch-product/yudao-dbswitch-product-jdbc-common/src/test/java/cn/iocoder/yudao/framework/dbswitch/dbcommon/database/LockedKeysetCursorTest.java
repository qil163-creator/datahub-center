package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LockedKeysetCursorTest {
    private static CursorReadRequest request(SourceGenerationPin pin) {
        return new CursorReadRequest("DBO", "SOURCE", List.of("ID"), "ID", List.of("ID"), null, null, null, BatchLimits.defaults(), true, pin);
    }
    private static SourceGenerationPin pin() { return new SourceGenerationPin("REPLAY", UUID.randomUUID().toString(), UUID.randomUUID().toString()); }
    private static LockedKeysetCursor.Shape shape() {
        return new LockedKeysetCursor.Shape("physical", new CursorKeyContract(ProductTypeEnum.SQLSERVER2000, "a".repeat(64), List.of(CursorKeyContract.Domain.integer(32, false))));
    }
    @Test void absentPinIsRejectedWithoutBorrowingOrCallingProduct() {
        DataSource source = mock(DataSource.class); var profile = mock(LockedKeysetCursor.Profile.class);
        assertThrows(IllegalArgumentException.class, () -> LockedKeysetCursor.open(source, request(null), profile, 10, 1));
        verifyNoInteractions(source, profile);
    }
    @Test void manualTransactionIsNotAdoptedOrRolledBack() throws Exception {
        DataSource source = mock(DataSource.class); var c = mock(Connection.class); var profile = mock(LockedKeysetCursor.Profile.class);
        when(source.getConnection()).thenReturn(c); when(c.getAutoCommit()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> LockedKeysetCursor.open(source, request(pin()), profile, 10, 1));
        verify(c).close(); verify(c, never()).rollback(); verify(c, never()).setAutoCommit(anyBoolean()); verifyNoInteractions(profile);
    }
    @Test void nativeLockFailureRollsBackRestoresAndClosesOwnedConnection() throws Exception {
        DataSource source = mock(DataSource.class); var c = mock(Connection.class); var profile = mock(LockedKeysetCursor.Profile.class);
        when(source.getConnection()).thenReturn(c); when(c.getAutoCommit()).thenReturn(true);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE);
        when(profile.lockAndInspect(eq(c), any(), eq(10))).thenThrow(new SQLException("Synthetic lock admission failure"));
        assertThrows(IllegalArgumentException.class, () -> LockedKeysetCursor.open(source, request(pin()), profile, 10, 1));
        var order = inOrder(c); order.verify(c).getAutoCommit(); order.verify(c).getTransactionIsolation();
        order.verify(c).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); order.verify(c).setAutoCommit(false);
        order.verify(c).getTransactionIsolation(); order.verify(c).rollback(); order.verify(c).setAutoCommit(true);
        order.verify(c).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); order.verify(c).close();
    }
    @Test void emptyFrozenSnapshotStillVerifiesPinAndPhysicallyClosesBeforeReturn() throws Exception {
        DataSource source = mock(DataSource.class); var c = mock(Connection.class); var profile = mock(LockedKeysetCursor.Profile.class);
        when(source.getConnection()).thenReturn(c); when(c.getAutoCommit()).thenReturn(true);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE);
        when(profile.lockAndInspect(eq(c), any(), eq(10))).thenReturn(shape()); var request = request(pin());
        var cursor = LockedKeysetCursor.open(source, request, profile, 10, 1);
        assertFalse(cursor.next()); verify(profile).verify(c, request, 10); cursor.close(); cursor.close();
        verify(c, times(1)).close(); verify(c, times(1)).rollback(); verify(c, never()).commit();
    }
    @Test void oldJdbcAbortLinkageFailureCannotSkipCloseOrClaimSuccessfulCleanup() throws Exception {
        DataSource source = mock(DataSource.class); var c = mock(Connection.class); var profile = mock(LockedKeysetCursor.Profile.class);
        when(source.getConnection()).thenReturn(c); when(c.getAutoCommit()).thenReturn(true);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE);
        when(profile.lockAndInspect(eq(c), any(), eq(10))).thenReturn(shape());
        var cursor = LockedKeysetCursor.open(source, request(pin()), profile, 10, 1);
        doThrow(new SQLException("Synthetic cleanup failure")).when(c).rollback(); doThrow(new AbstractMethodError()).when(c).abort(any());
        assertThrows(SQLException.class, cursor::close); verify(c).close(); verify(c, never()).setAutoCommit(true);
    }
}
