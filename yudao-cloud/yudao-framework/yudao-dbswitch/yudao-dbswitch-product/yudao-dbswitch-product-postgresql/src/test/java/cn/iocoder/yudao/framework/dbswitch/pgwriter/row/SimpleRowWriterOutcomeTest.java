package cn.iocoder.yudao.framework.dbswitch.pgwriter.row;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.BinaryWriteFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimpleRowWriterOutcomeTest {
    CopyIn copy;
    SimpleRowWriter writer;

    @BeforeEach void setup() throws Exception {
        var connection = mock(PGConnection.class);
        var manager = mock(CopyManager.class);
        copy = mock(CopyIn.class);
        var active = new AtomicBoolean(true);
        when(connection.getCopyAPI()).thenReturn(manager);
        when(manager.copyIn(anyString())).thenReturn(copy);
        when(copy.isActive()).thenAnswer(invocation -> active.get());
        when(copy.endCopy()).thenAnswer(invocation -> { active.set(false); return 1L; });
        when(copy.getHandledRowCount()).thenReturn(1L);
        doAnswer(invocation -> { active.set(false); return null; }).when(copy).cancelCopy();
        writer = new SimpleRowWriter(new SimpleRowWriter.Table("fixture", new String[]{"id"}), connection, true);
    }

    @Test void finishReturnsDatabaseAcknowledgementAndCloseIsIdempotent() throws Exception {
        writer.startRow(row -> row.setInteger(0, 1));
        assertEquals(1, writer.finish());
        assertTrue(writer.isProtocolFinished());
        assertEquals(1, writer.finish());
        writer.close(); writer.abort();
        verify(copy, times(1)).endCopy(); verify(copy, never()).cancelCopy();
        assertThrows(BinaryWriteFailedException.class, () -> writer.startRow(row -> row.setInteger(0, 2)));
    }

    @Test void legacyCloseStillCompletesButDoesNotLoseTheAcknowledgement() throws Exception {
        writer.startRow(row -> row.setInteger(0, 1));
        writer.close();
        assertEquals(1, writer.finish()); verify(copy, times(1)).endCopy();
    }

    @Test void consumerFailureCancelsInsteadOfCompletingThePrefix() throws Exception {
        writer.startRow(row -> row.setInteger(0, 1));
        var failure = new IllegalArgumentException("conversion");
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> writer.startRow(row -> { throw failure; })));
        writer.close(); writer.abort();
        assertThrows(BinaryWriteFailedException.class, writer::finish);
        verify(copy).cancelCopy(); verify(copy, never()).endCopy();
        verify(copy, never()).writeToCopy(any(byte[].class), anyInt(), anyInt());
    }

    @Test void explicitAbortNeverFlushesOrCompletes() throws Exception {
        writer.startRow(row -> row.setInteger(0, 1));
        writer.abort(); writer.abort(); writer.close();
        assertTrue(writer.isProtocolFinished());
        verify(copy).cancelCopy(); verify(copy, never()).endCopy();
        verify(copy, never()).writeToCopy(any(byte[].class), anyInt(), anyInt());
        assertThrows(BinaryWriteFailedException.class, writer::finish);
    }

    @Test void fatalConversionFailureAlsoCancels() throws Exception {
        var fatal = new AssertionError("synthetic fatal");
        assertSame(fatal, assertThrows(AssertionError.class, () -> writer.startRow(row -> { throw fatal; })));
        verify(copy).cancelCopy(); verify(copy, never()).endCopy();
    }

    @Test void cancellationFailureDoesNotMaskTheOriginalFailureOrRetryCompletion() throws Exception {
        doThrow(new SQLException("cancel failed")).when(copy).cancelCopy();
        var original = new IllegalArgumentException("conversion");
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> writer.startRow(row -> { throw original; })));
        assertEquals(1, original.getSuppressed().length);
        assertFalse(writer.isProtocolFinished());
        writer.close(); writer.abort();
        assertThrows(BinaryWriteFailedException.class, writer::finish);
        verify(copy, times(1)).cancelCopy(); verify(copy, never()).endCopy();
    }

    @ParameterizedTest @ValueSource(strings = {"end", "write", "count"})
    void failedCompletionCannotBeReportedAsFinished(String fault) throws Exception {
        if (fault.equals("end")) doThrow(new SQLException("end failed")).when(copy).endCopy();
        if (fault.equals("write")) doThrow(new SQLException("write failed")).when(copy)
                .writeToCopy(any(byte[].class), anyInt(), anyInt());
        if (fault.equals("count")) when(copy.getHandledRowCount()).thenReturn(-1L);
        writer.startRow(row -> row.setInteger(0, 1));
        assertThrows(BinaryWriteFailedException.class, writer::finish);
        writer.close(); writer.abort();
        assertThrows(BinaryWriteFailedException.class, writer::finish);
        verify(copy, atMostOnce()).endCopy();
        if (!fault.equals("count")) verify(copy).cancelCopy();
    }
}
