package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgArray;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcArrayValueTest {
    @Test void detachesTypedElementsOnceAndFreesLocatorWithoutUsingItsStringAsData() throws Exception {
        var array = array(); var values = mock(ResultSet.class);
        when(array.getResultSet()).thenReturn(values);
        when(values.next()).thenReturn(true, true, true, false);
        when(values.getLong(1)).thenReturn(1L, 2L, 3L);
        when(values.getObject(2)).thenReturn(null, "", "中文\\NULL\"");
        Object[] input = {array, array};
        var batch = BatchValueConverter.convert(List.<Object[]>of(input), 2);
        var detached = assertInstanceOf(JdbcArrayValue.class, batch.getFirst()[0]);
        assertSame(detached, batch.getFirst()[1]);
        assertEquals(Arrays.asList(null, "", "中文\\NULL\""), detached.elements());
        assertEquals("text", detached.baseTypeName());
        assertThrows(UnsupportedOperationException.class, () -> detached.elements().set(0, "changed"));
        Object[] copy = detached.elementsArray(); copy[1] = "changed";
        assertEquals("", detached.elements().get(1));
        assertFalse(detached.toString().contains("中文"));
        verify(array).getResultSet(); verify(array).free(); verify(values).close();
        assertSame(array, input[0]);
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toText(detached));
    }

    @Test void limitAndReadFailureReleaseTheSourceLocatorAndRemoveDriverSecrets() throws Exception {
        for (boolean readFailure : List.of(false, true)) {
            var array = array(); var values = mock(ResultSet.class);
            when(array.getResultSet()).thenReturn(values); when(values.next()).thenReturn(true);
            when(values.getLong(1)).thenReturn(1L);
            if (readFailure) when(values.getObject(2)).thenThrow(new java.sql.SQLException("sensitive-array-value"));
            else when(values.getObject(2)).thenReturn("x".repeat(40));
            var failure = assertThrows(BatchRejectedException.class, () -> BatchValueConverter.convert(
                    List.<Object[]>of(new Object[]{array}), 1, new BatchLimits(10, 1000, 80, 100, 1000)));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("sensitive-array-value"));
            verify(values).close(); verify(array).free();
        }
    }

    @Test void binaryNonDefaultBoundsAndNestedArraysAreRejectedBeforeElementDecoding() throws Exception {
        for (int dimensions : List.of(1, 2)) {
            var array = array(); when(array.isBinary()).thenReturn(true);
            byte[] header = java.nio.ByteBuffer.allocate(28).putInt(dimensions).putInt(0).putInt(23)
                    .putInt(2).putInt(0).array();
            when(array.toBytes()).thenReturn(header);
            assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.normalize(array));
            verify(array, never()).getResultSet(); verify(array).free();
        }
    }

    private PgArray array() throws Exception {
        var array = mock(PgArray.class);
        when(array.toString()).thenReturn("{shape-only}");
        when(array.getBaseTypeName()).thenReturn("text"); when(array.getBaseType()).thenReturn(Types.VARCHAR);
        return array;
    }
}
