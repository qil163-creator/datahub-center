package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxdbWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.math.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfluxdbWriterSafetyTest {
    private static final List<String> FIELDS=List.of("time","value");
    private static final Instant TIME=Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static InfluxNativeCatalog.Table table(InfluxNativeCatalog.Kind kind){
        return new InfluxNativeCatalog.Table("samples",List.of(new InfluxNativeCatalog.Column("time",InfluxNativeCatalog.Kind.TIME),new InfluxNativeCatalog.Column("value",kind)));
    }
    static Stream<Object> unsupportedFields(){return Stream.of(new BigDecimal("1.25"),new BigDecimal("9007199254740993"),BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),Double.NaN,Double.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,new byte[]{1,2});}
    @ParameterizedTest @MethodSource("unsupportedFields")
    void laterUnsupportedFieldPreventsEveryPointFromBeingSubmitted(Object value){
        var source=mock(DataSource.class);var writer=prepared(source);
        var failure=assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,List.of(new Object[]{TIME,1L},new Object[]{TIME,value})));
        assertNull(failure.getCause());verifyNoInteractions(source);
    }
    static Stream<Object> invalidTimestamps(){return Stream.of(null,"not-an-instant",LocalDateTime.of(2026,1,1,0,0),new BigDecimal("1.1"),new BigInteger("9223372036854775808"),Double.NaN,Instant.parse("3000-01-01T00:00:00Z"));}
    @ParameterizedTest @MethodSource("invalidTimestamps")
    void invalidTimestampNeverBecomesCurrentTimeOrWritesEarlierRows(Object value){
        var source=mock(DataSource.class);var writer=prepared(source);
        assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,List.of(new Object[]{TIME,1L},new Object[]{value,2L})));verifyNoInteractions(source);
    }
    @Test void signedIntegerBoundaryAndNanosecondsRemainExactWithoutInputMutation(){
        var integer=BigInteger.valueOf(Long.MIN_VALUE);var row=new Object[]{TIME,integer};
        String line=new String(InfluxLineProtocol.batch(table(InfluxNativeCatalog.Kind.INT64),FIELDS,List.<Object[]>of(row),4096),StandardCharsets.UTF_8);
        assertEquals("samples value=-9223372036854775808i 1767225600123456789\n",line);assertSame(integer,row[1]);
    }
    @Test void unsignedIntegerHasNativeUnsignedSuffixAndExactBounds(){
        var nativeTable=table(InfluxNativeCatalog.Kind.UINT64);
        assertEquals("samples value=18446744073709551615u 1767225600123456789\n",new String(InfluxLineProtocol.batch(nativeTable,FIELDS,List.<Object[]>of(new Object[]{TIME,new BigInteger("18446744073709551615")}),4096),StandardCharsets.UTF_8));
        for(var value:List.of(new BigInteger("-1"),new BigInteger("18446744073709551616"),new BigDecimal("1.5")))
            assertThrows(IllegalArgumentException.class,()->InfluxLineProtocol.batch(nativeTable,FIELDS,List.<Object[]>of(new Object[]{TIME,value}),4096));
    }
    @Test void allNullFieldsAndRaggedRowsNeverBorrowAConnection(){
        var source=mock(DataSource.class);var writer=prepared(source);
        assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,List.<Object[]>of(new Object[]{TIME,null})));
        assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,List.of(new Object[]{TIME,1L},new Object[]{TIME})));verifyNoInteractions(source);
    }
    @Test void integralDecimalTimestampMeansExactEpochMilliseconds(){
        assertEquals(1767225600123000000L,InfluxLineProtocol.nanos(new BigDecimal("1767225600123.000")));
    }
    @Test void timestampAndEveryTagMustBeMappedAndTagValuesCannotBecomeFields(){
        var nativeTable=new InfluxNativeCatalog.Table("samples",List.of(new InfluxNativeCatalog.Column("time",InfluxNativeCatalog.Kind.TIME),new InfluxNativeCatalog.Column("tag",InfluxNativeCatalog.Kind.TAG),new InfluxNativeCatalog.Column("value",InfluxNativeCatalog.Kind.STRING)));
        assertThrows(IllegalArgumentException.class,()->InfluxLineProtocol.fields(nativeTable,FIELDS));
        assertEquals("samples,tag=zone\\ 1 value=\"abc\" 1767225600123456789\n",new String(InfluxLineProtocol.batch(nativeTable,List.of("time","tag","value"),List.<Object[]>of(new Object[]{TIME,"zone 1","abc"}),4096),StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,()->InfluxLineProtocol.batch(nativeTable,List.of("time","tag","value"),List.<Object[]>of(new Object[]{TIME,1L,"abc"}),4096));
    }
    @Test void invalidUtf8NewlinesAndEncodedByteBudgetAreRejected(){
        for(String value:List.of("a\nb","a\rb","x\uD800"))
            assertThrows(IllegalArgumentException.class,()->InfluxLineProtocol.batch(table(InfluxNativeCatalog.Kind.STRING),FIELDS,List.<Object[]>of(new Object[]{TIME,value}),4096));
        assertThrows(IllegalArgumentException.class,()->InfluxLineProtocol.batch(table(InfluxNativeCatalog.Kind.STRING),FIELDS,List.<Object[]>of(new Object[]{TIME,"中文"}),20));
    }
    private static InfluxdbWriterImpl prepared(DataSource source){
        var writer=new InfluxdbWriterImpl(source);ReflectionTestUtils.setField(writer,"prepared",table(InfluxNativeCatalog.Kind.INT64));ReflectionTestUtils.setField(writer,"preparedFields",FIELDS);return writer;
    }
}
