package cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopySessionOutputStreamTest {
    @ParameterizedTest @ValueSource(strings = {"write", "flush"})
    void closeAfterFailedIoCannotFinishThePrefix(String fault) throws Exception {
        var session = mock(CopySession.class); var stream = new CopySessionOutputStream(session);
        if (fault.equals("write")) {
            doThrow(new SQLException("write failed")).when(session).writeToCopy(any(byte[].class), anyInt(), anyInt());
            assertThrows(IOException.class, () -> stream.write(new byte[]{1}, 0, 1));
        } else {
            doThrow(new SQLException("flush failed")).when(session).flushCopy();
            assertThrows(IOException.class, stream::flush);
        }
        assertThrows(IOException.class, stream::close); stream.close();
        verify(session, never()).endCopy(); verify(session, never()).cancelCopy();
    }

    @Test void successfulCloseSendsExactlyOneEndMarkerAndDoesNotInventCommit() throws Exception {
        var session = mock(CopySession.class); var stream = new CopySessionOutputStream(session);
        byte[] bytes = {1, 2}; stream.write(bytes); stream.flush(); stream.close(); stream.close();
        verify(session).writeToCopy(bytes, 0, 2); verify(session).flushCopy(); verify(session).endCopy();
        verifyNoMoreInteractions(session);
    }
}
