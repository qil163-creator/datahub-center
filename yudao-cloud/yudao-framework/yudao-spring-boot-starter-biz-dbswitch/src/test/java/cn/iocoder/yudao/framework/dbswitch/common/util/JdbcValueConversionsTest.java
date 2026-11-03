package cn.iocoder.yudao.framework.dbswitch.common.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLXML;
import java.sql.Struct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcValueConversionsTest {
    @Test void copyTextRejectsUnpairedSurrogatesWithoutPublishingValues() {
        for (String value : List.of("private-\uD800", "private-\uDC00", "private-\uDC00\uD800",
                "private-\uD800x", "private-\uD800\uD800", "private-\uD800\uDC00\uDC00")) {
            for (int type : new int[]{java.sql.Types.VARCHAR, java.sql.Types.NVARCHAR,
                    java.sql.Types.CHAR, java.sql.Types.CLOB, java.sql.Types.OTHER}) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> JdbcValueConversions.forJdbcType(value, type));
                assertNull(failure.getCause());
                assertFalse(failure.getMessage().contains("private-"));
            }
        }
    }

    @Test void copyTextPreservesWellFormedUnicodeAndNull() {
        String text = "中文 😀 \uD800\uDC00 \uDBFF\uDFFF";
        assertSame(text, JdbcValueConversions.forJdbcType(text, java.sql.Types.VARCHAR));
        assertEquals("", JdbcValueConversions.forJdbcType("", java.sql.Types.VARCHAR));
        assertNull(JdbcValueConversions.forJdbcType(null, java.sql.Types.VARCHAR));
    }

    @Test void binaryToTextRejectsMalformedUtf8InsteadOfReplacingBytes() {
        String text="中文，emoji 😀";byte[] valid=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(text,JdbcValueConversions.toText(valid));
        for(byte[] invalid:new byte[][]{{(byte)0xc3,0x28},{(byte)0xc0,(byte)0xaf},{(byte)0xf0,(byte)0x9f},{(byte)0x80}}){
            byte[] original=invalid.clone();
            var error=assertThrows(IllegalArgumentException.class,()->JdbcValueConversions.toText(invalid));
            assertNull(error.getCause());assertTrue(error.getMessage().contains("UTF-8"));
            assertArrayEquals(original,invalid);assertArrayEquals(original,JdbcValueConversions.toBytes(invalid));
        }
    }
    @Test void numericObjectsRemainExactAcrossCommonEntryPoints() {
        var decimal = new BigDecimal("123456789012345678901234567890.12345678901234567890");
        var integer = new BigInteger("123456789012345678901234567890");
        for (Object value : List.of(decimal, integer)) {
            assertSame(value, TypeConvertUtils.castByDetermine(value));
            assertSame(value, JdbcValueConversions.normalize(value));
        }
        assertEquals(new BigDecimal(integer), JdbcValueConversions.toDecimal(integer));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toInteger("2147483648"));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toLong("1.5"));
    }

    @Test void booleanAndUnsupportedStructCannotSilentlyBecomeFalseOrNull() {
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toBoolean("private-invalid-value"));
        assertTrue(JdbcValueConversions.toBoolean(new BigDecimal("0.25")));
        assertFalse(JdbcValueConversions.toBoolean("false"));
        assertThrows(IllegalArgumentException.class, () -> TypeConvertUtils.castByDetermine(mock(Struct.class)));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toBytes(new Object()));
        assertNull(JdbcValueConversions.normalize(null));
    }

    @Test void timeAndTimestampPreserveFractions() {
        assertEquals(LocalTime.of(12, 34, 56, 123456789), JdbcValueConversions.toTime("12:34:56.123456789"));
        var expected = LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456789);
        assertEquals(expected, JdbcValueConversions.toDateTime(java.sql.Timestamp.valueOf(expected)));
    }

    @Test void xmlAndRepeatedClobAreMaterializedOnceOnCallingThreadWithoutReplacingInput() throws Exception {
        var owner = Thread.currentThread();
        var reader = spy(new StringReader("first\nsecond"));
        var clob = mock(Clob.class);
        when(clob.getCharacterStream()).thenAnswer(call -> { assertSame(owner, Thread.currentThread()); return reader; });
        var xml = mock(SQLXML.class);
        when(xml.getCharacterStream()).thenReturn(new StringReader("<root>value</root>"));
        var row = new Object[]{clob, clob, xml};
        List<Object[]> source = List.<Object[]>of(row);
        var converted = BatchValueConverter.convert(source, 3);
        assertNotSame(row, converted.get(0));
        assertArrayEquals(new Object[]{"first\nsecond", "first\nsecond", "<root>value</root>"}, converted.get(0));
        assertArrayEquals(new Object[]{clob, clob, xml}, source.get(0));
        verify(clob).getCharacterStream(); verify(clob).free(); verify(reader).close(); verify(xml).free();
    }

    @Test void malformedShapeFailsBeforeAnyLocatorIsConsumed() throws Exception {
        var clob = mock(Clob.class);
        assertThrows(IllegalArgumentException.class,
                () -> BatchValueConverter.convert(List.of(new Object[]{clob}, new Object[]{1, 2}), 1));
        verifyNoInteractions(clob);
    }

    @Test void lobFailureClosesAndFreesWithoutPublishingDriverDetails() throws Exception {
        var reader = mock(Reader.class);
        when(reader.read(any(char[].class))).thenThrow(new IOException("private-source-value"));
        var clob = mock(Clob.class); when(clob.getCharacterStream()).thenReturn(reader);
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeConvertUtils.clob2Str(clob));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-source-value"));
        verify(reader).close(); verify(clob).free();
        var xml = mock(SQLXML.class);
        when(xml.getCharacterStream()).thenThrow(new java.sql.SQLException("private-source-value"));
        assertThrows(IllegalArgumentException.class, () -> TypeConvertUtils.castByDetermine(xml));
        verify(xml).free();
    }

    @Test void blobLengthMismatchAndOversizeFailInsteadOfReturningTruncatedOrZeroFilledBytes() throws Exception {
        for (long declared : new long[]{1, 3, Long.MAX_VALUE}) {
            var stream = spy(new ByteArrayInputStream(new byte[]{1, 2}));
            var blob = mock(Blob.class); when(blob.length()).thenReturn(declared); when(blob.getBinaryStream()).thenReturn(stream);
            assertThrows(IllegalArgumentException.class, () -> TypeConvertUtils.blob2Bytes(blob));
            verify(stream).close(); verify(blob).free();
        }
        var blob = mock(Blob.class); when(blob.length()).thenReturn(2L);
        when(blob.getBinaryStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        assertArrayEquals(new byte[]{1, 2}, TypeConvertUtils.blob2Bytes(blob)); verify(blob).free();
    }

    @Test void interruptIsPreservedAndDoesNotConsumeLocators() {
        var clob = mock(Clob.class);
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> BatchValueConverter.convert(List.<Object[]>of(new Object[]{clob}), 1));
            assertTrue(Thread.currentThread().isInterrupted()); verifyNoInteractions(clob);
        } finally { Thread.interrupted(); }
    }

    @Test void sizeEstimationCannotConsumeOrFreeLocatorsBeforeTheWriter() throws Exception {
        var blob = mock(Blob.class); when(blob.length()).thenReturn(17L);
        var clob = mock(Clob.class); when(clob.length()).thenReturn(19L);
        assertEquals(17, JdbcTypesUtils.getObjectSize(java.sql.Types.BLOB, blob));
        assertEquals(38, JdbcTypesUtils.getObjectSize(java.sql.Types.CLOB, clob));
        verify(blob).length(); verify(clob).length(); verifyNoMoreInteractions(blob, clob);
    }
}
