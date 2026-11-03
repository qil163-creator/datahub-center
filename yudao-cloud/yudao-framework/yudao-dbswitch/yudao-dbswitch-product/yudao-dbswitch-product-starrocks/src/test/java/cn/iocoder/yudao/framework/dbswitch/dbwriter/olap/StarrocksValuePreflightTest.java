package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StarrocksValuePreflightTest {
    @Test void everyIntegralDomainRejectsFractionAndOverflowBeforeProducingABatch() {
        for(var column:List.of(column("tinyint",Types.TINYINT),column("smallint",Types.SMALLINT),column("int",Types.INTEGER),column("bigint",Types.BIGINT),column("largeint",Types.DECIMAL))) {
            for(Object value:List.of(new BigDecimal("1.5"),BigInteger.ONE.shiftLeft(128)))assertThrows(IllegalArgumentException.class,()->convert(column,1,value));
        }
        assertEquals(new BigDecimal(BigInteger.ONE.shiftLeft(127).negate()),convert(column("largeint",Types.DECIMAL),BigInteger.ONE.shiftLeft(127).negate()).getFirst()[0]);
        assertEquals(new BigDecimal(BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)),convert(column("largeint",Types.DECIMAL),BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)).getFirst()[0]);
        assertThrows(IllegalArgumentException.class,()->convert(column("largeint",Types.DECIMAL),BigInteger.ONE.shiftLeft(127)));
        assertThrows(IllegalArgumentException.class,()->convert(column("largeint",Types.DECIMAL),BigInteger.ONE.shiftLeft(127).negate().subtract(BigInteger.ONE)));
    }
    @Test void localDatetimeHasMicrosecondPrecisionAndZonedValuesRequireAnExplicitConversion() {
        var column=column("datetime",Types.TIMESTAMP);var good=LocalDateTime.of(2026,10,3,12,0,0,123456000);var bad=good.withNano(123456789);
        assertEquals(good,convert(column,Timestamp.valueOf(good)).getFirst()[0]);assertEquals(good,convert(column,good).getFirst()[0]);
        for(Object value:List.of(bad,Timestamp.valueOf(bad),bad.atOffset(ZoneOffset.UTC),bad.toInstant(ZoneOffset.UTC),good.atOffset(ZoneOffset.UTC),good.toInstant(ZoneOffset.UTC)))
            assertThrows(IllegalArgumentException.class,()->convert(column,good,value));
    }
    @Test void textIsBoundedByNativeUtf8BytesAndMalformedSurrogatesCannotBeSent() {
        var column=column("varchar(4)",Types.VARCHAR);
        assertEquals("😀",convert(column,"😀").getFirst()[0]);
        assertThrows(IllegalArgumentException.class,()->convert(column,"ok","中文"));
        assertThrows(IllegalArgumentException.class,()->convert(column,"ok","\uD800"));
    }
    @Test void approximateTargetsRequireAnExactWideningOrExplicitApproximation() {
        assertEquals(0.5f,convert(column("float",Types.REAL),new BigDecimal("0.5")).getFirst()[0]);
        assertEquals(0.5d,convert(column("double",Types.DOUBLE),new BigDecimal("0.5")).getFirst()[0]);
        assertThrows(IllegalArgumentException.class,()->convert(column("float",Types.REAL),new BigDecimal("0.1")));
        assertThrows(IllegalArgumentException.class,()->convert(column("double",Types.DOUBLE),new BigDecimal("0.1")));
        assertThrows(IllegalArgumentException.class,()->convert(column("float",Types.REAL),16777217));
        assertThrows(IllegalArgumentException.class,()->convert(column("double",Types.DOUBLE),BigInteger.valueOf(9007199254740993L)));
    }
    @Test void binaryLengthAndTypeAreValidatedForTheWholeBatchWithoutMutatingInputs() {
        var column=column("varbinary",Types.VARBINARY);
        byte[] maximum=new byte[StarrocksJdbcSupport.MAX_VARBINARY_BYTES];maximum[maximum.length-1]=(byte)255;
        byte[] copy=(byte[])convert(column,(Object)maximum).getFirst()[0];assertNotSame(maximum,copy);assertArrayEquals(maximum,copy);
        byte[] oversized=new byte[StarrocksJdbcSupport.MAX_VARBINARY_BYTES+1];
        assertThrows(IllegalArgumentException.class,()->convert(column,new byte[]{0,1},oversized));
        assertThrows(IllegalArgumentException.class,()->convert(column,new byte[]{0,1},"0001"));
        assertEquals((byte)255,maximum[maximum.length-1]);assertEquals(0,oversized[oversized.length-1]);
    }
    private static StarrocksJdbcSupport.Column column(String nativeType,int jdbc){return new StarrocksJdbcSupport.Column("value",nativeType,jdbc,0,0,true,"");}
    private static List<Object[]> convert(StarrocksJdbcSupport.Column column,Object...values){
        var table=new StarrocksJdbcSupport.Table("a".repeat(64),"1:1","PRIMARY_KEYS",List.of(column),List.of("value"),List.of("value"),1,Map.of());
        return StarrocksJdbcSupport.convert(Arrays.stream(values).map(value->new Object[]{value}).toList(),List.of("value"),table,BatchLimits.defaults());
    }
}
