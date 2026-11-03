package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLXML;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchBudgetTest {
    @Test void preparedBatchAndMultiValuesHaveDifferentParameterBudgets() {
        var limits = new BatchLimits(1000, 1024, 512, 10, 100);
        assertDoesNotThrow(() -> limits.requireStatementParameters(4));
        assertEquals(2, limits.maxMultiValueRows(4, 1));
        assertThrows(IllegalArgumentException.class, () -> limits.requireStatementParameters(11));
        assertThrows(IllegalArgumentException.class, () -> limits.maxMultiValueRows(4, 7));
    }

    @Test void rowAndBatchLimitsAreCheckedWithoutMutatingInput() {
        var limits = new BatchLimits(2, 64, 32, 10, 100);
        var row = new Object[]{"12345678"}; var source = List.<Object[]>of(row);
        var converted = BatchValueConverter.convert(source, 1, limits);
        assertNotSame(row, converted.getFirst()); assertEquals("12345678", row[0]);
        assertThrows(IllegalArgumentException.class, () -> BatchValueConverter.convert(List.of(row, row), 1, limits));
        assertThrows(IllegalArgumentException.class, () -> BatchValueConverter.convert(List.of(row, row, row), 1, limits));
    }

    @Test void blobDeclaredOverLimitNeverMaterializesAndStillReleasesResources() throws Exception {
        var blob = mock(Blob.class); var input = spy(new ByteArrayInputStream(new byte[]{1, 2}));
        when(blob.length()).thenReturn(100L); when(blob.getBinaryStream()).thenReturn(input);
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.normalize(blob, 8));
        verify(blob).free(); verify(input).close(); verify(input, never()).read(any(byte[].class), anyInt(), anyInt());
    }

    @Test void textAndXmlAreBoundedWhileReadingAndReleasedOnLimitFailure() throws Exception {
        var clob = mock(Clob.class); var clobReader = spy(new StringReader("123456789"));
        when(clob.getCharacterStream()).thenReturn(clobReader);
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.normalize(clob, 8));
        verify(clob).free(); verify(clobReader).close();
        var xml = mock(SQLXML.class); var xmlReader = spy(new StringReader("<root>too much</root>"));
        when(xml.getCharacterStream()).thenReturn(xmlReader);
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.normalize(xml, 8));
        verify(xml).free(); verify(xmlReader).close(); verify(xml, never()).getString();
    }
}
