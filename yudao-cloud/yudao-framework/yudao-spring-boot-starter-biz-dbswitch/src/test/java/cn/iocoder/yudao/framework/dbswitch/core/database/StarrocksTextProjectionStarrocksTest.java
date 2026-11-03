package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.starrocks.url", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class StarrocksTextProjectionStarrocksTest {
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.starrocks.url"), "root", "");
    }
    @ParameterizedTest @CsvSource(delimiter='|', value={
            "CHAR(5)|0|0", "VARCHAR(500)|12|12", "STRING|12|12", "SMALLINT|0|5", "INT|0|4",
            "BIGINT|0|-5", "TINYINT|0|0", "LARGEINT|0|0", "DECIMAL(20,0)|0|0", "DOUBLE|0|0"})
    void wireVarcharCannotMasqueradeAsNativeText(String type, int textType, int mixedType) throws Exception {
        try (var c=connect(); var sql=c.createStatement()) {
            sql.setQueryTimeout(10);
            var database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.STARROCKS);
            var p=database.textKeyProjection("s","id",256);
            var mixed=database.textAndIntegerKeyComponentProjection(c,"s","id",256);
            for (String source : List.of("SELECT CAST('9' AS "+type+") AS id",
                    "SELECT id FROM (SELECT CAST('9' AS "+type+") AS id) v GROUP BY id")) {
                try(var rows=sql.executeQuery("SELECT "+p.valueSql()+","+p.typeSql()+","+mixed.typeSql()+" FROM ("+source+") s")) {
                    assertTrue(rows.next());
                    assertEquals(0,p.jdbcType(rows.getMetaData(),2)); // Metadata-only callers stay closed.
                    assertEquals(textType,p.jdbcType(rows,2));
                    assertEquals(mixedType,mixed.jdbcType(rows,3));
                    assertFalse(rows.next());
                }
            }
            assertFalse(c.isClosed()); assertTrue(c.getAutoCommit());
        }
    }
    @ParameterizedTest @ValueSource(ints={1,256,4096})
    void byteBudgetNeverTruncates(int bound) throws Exception {
        try(var c=connect();var sql=c.createStatement()){
            var p=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.STARROCKS).textKeyProjection("s","id",bound);
            for(int length:new int[]{bound,bound+1}){
                try(var rows=sql.executeQuery("SELECT "+p.valueSql()+" FROM (SELECT repeat('x',"+length+") AS id) s")){
                    assertTrue(rows.next());
                    if(length==bound) assertEquals(bound,rows.getBytes(1).length); else assertNull(rows.getBytes(1));
                }
            }
        }
    }
    @Test void exactFilteringSeparatesUnicodeAndQuotedKeys() throws Exception {
        try(var c=connect();var sql=c.createStatement()){
            var p=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.STARROCKS).textKeyProjection("s","id",256);
            var values=List.of("A","a","A ","0009","9","中国😀","e\u0301","é","a\\' OR 1=1 -- ");
            var source=String.join(" UNION ALL ",values.stream().map(v->"SELECT unhex('"+
                    HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))+"') AS id").toList());
            for(String v:values){
                try(var rows=sql.executeQuery("SELECT "+p.valueSql()+" FROM ("+source+") s WHERE "+p.equalitySql(v))){
                    assertTrue(rows.next());assertArrayEquals(v.getBytes(StandardCharsets.UTF_8),rows.getBytes(1));assertFalse(rows.next());
                }
            }
            assertThrows(IllegalArgumentException.class,()->p.equalitySql("\uD800"));
        }
    }
}
