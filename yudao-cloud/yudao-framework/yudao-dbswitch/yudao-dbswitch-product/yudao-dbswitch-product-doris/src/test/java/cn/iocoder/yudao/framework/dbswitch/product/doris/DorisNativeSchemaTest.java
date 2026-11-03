package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

class DorisNativeSchemaTest {
    static String ddl(String model){return "CREATE TABLE `sample` (`part` SMALLINT NOT NULL COMMENT '',`id` BIGINT NOT NULL COMMENT '',`amount` DECIMAL(38,18) NULL COMMENT '',`note` VARCHAR(256) NULL COMMENT '',`at` DATETIME(6) NULL COMMENT '') ENGINE=OLAP "+model+" KEY(`part`,`id`) COMMENT 'source' DISTRIBUTED BY HASH(`part`,`id`) BUCKETS 1 PROPERTIES(\"replication_allocation\"=\"tag.location.default: 1\",\"enable_unique_key_merge_on_write\"=\"true\")";}
    @Test void nativeModelPrecisionNullabilityAndOrderSurviveStructuredRoundTrip(){var definition=parse(ddl("UNIQUE"));assertTrue(definition.unique());assertEquals(List.of("part","id"),definition.keys());assertEquals(18,definition.column("amount").scale());assertEquals(6,definition.column("at").scale());assertEquals(definition,parse(definition.ddl("target","copy")));assertFalse(parse(ddl("DUPLICATE")).unique());}
    @Test void literalsAndPhysicalNamesRemainDataWhileMapping(){var definition=parse("CREATE TABLE `a'b,(` (`a``b` BIGINT NOT NULL COMMENT 'c\\\\d\\\'e',`text` VARCHAR(20) DEFAULT 'x\\\\y\\n' COMMENT '') ENGINE=OLAP UNIQUE KEY(`a``b`) DISTRIBUTED BY HASH(`a``b`) BUCKETS 1 PROPERTIES('replication_num'='1')");
        var mapped=definition.map(Map.of("a`b","c'd,(","text","select"));assertEquals("c\\d'e",mapped.column("c'd,(").comment());assertEquals(List.of("c'd,("),mapped.keys());
        // The server strips backslashes from column COMMENT; rendering this catalog value must fail.
        assertThrows(IllegalArgumentException.class,()->mapped.ddl("db","target"));
        var safe=parse(ddl("UNIQUE")).map(Map.of("part","a'b","id","c`d","amount","amount","note","note","at","at"));
        assertEquals(safe,parse(safe.ddl("db","target")));}
    @Test void columnCommentsPreserveQuotesAndActualNewlinesButRejectBackslashes(){String value="中文 'one' \"two\"\nnext";
        var definition=parse(ddl("UNIQUE").replace("COMMENT 'source'","COMMENT "+literal(value)).replaceFirst("COMMENT ''",java.util.regex.Matcher.quoteReplacement("COMMENT "+columnCommentLiteral(value))));
        assertEquals(value,definition.column("part").comment());assertEquals(definition,parse(definition.ddl("db","copy")));
        assertThrows(IllegalArgumentException.class,()->columnCommentLiteral("a\\b"));}
    @Test void showCreateDoubleQuoteEscapingRequiresAnExactCatalogWitness(){String comment="table'\"\\end\nnext";
        String nativeDdl=ddl("UNIQUE").replace("COMMENT 'source'","COMMENT '"+comment.replace("\"","\\\"")+"'");
        Map<String,String> comments=Map.of("part","","id","","amount","","note","","at","");
        assertEquals(comment,parseCatalog(nativeDdl,comment,comments).comment());
        assertThrows(IllegalArgumentException.class,()->parseCatalog(nativeDdl,comment+"x",comments));}
    @Test void unescapedShowCreateCommentsNeedAnIndependentExactCatalogWitness(){String ddl="CREATE TABLE t(id BIGINT NOT NULL COMMENT 'a'b') ENGINE=OLAP UNIQUE KEY(id) COMMENT 'table'\\end' DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";assertThrows(IllegalArgumentException.class,()->parse(ddl));var table=parseCatalog(ddl,"table'\\end",Map.of("id","a'b"));assertEquals("table'\\end",table.comment());assertEquals("a'b",table.column("id").comment());assertEquals(table,parse(table.ddl("db","t")));assertThrows(IllegalArgumentException.class,()->parseCatalog(ddl,"table'\\end",Map.of("id","wrong")));}
    @ParameterizedTest @ValueSource(strings={"datetime(7)","decimal(39,0)","decimal(4,5)","varchar(65534)","char(256)","bitmap","hll","array<int>","varchar(1);DROP TABLE x"})
    void unimplementedOrInvalidNativeTypesCannotBecomeString(String type){assertThrows(IllegalArgumentException.class,()->type(type));}
    @Test void extraStatementsExpressionsPartitionsAndAggregatesDoNotBecomeAHeap(){String ddl=ddl("UNIQUE");for(String value:List.of(ddl+"; DROP TABLE t",ddl.replace(" NULL COMMENT ''"," DEFAULT now() COMMENT ''"),ddl.replace("DISTRIBUTED","PARTITION BY RANGE(id) () DISTRIBUTED"),ddl.replace("UNIQUE KEY","AGGREGATE KEY")))assertThrows(IllegalArgumentException.class,()->parse(value));}
    @Test void exactValuesKeepDecimalLargeIntegerAndTimestamp(){var decimal=new Column("d","decimal(38,18)",false,null,"");BigDecimal input=new BigDecimal("12345678901234567890.123456789012345678");assertEquals(input,DorisJdbcSupport.value(input,decimal));
        var large=new Column("n","largeint",false,null,"");BigInteger min=BigInteger.ONE.shiftLeft(127).negate();assertEquals(new BigDecimal(min),DorisJdbcSupport.value(min,large));assertEquals(new BigDecimal(BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)),DorisJdbcSupport.value(BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE),large));
        assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value(min.subtract(BigInteger.ONE),large));assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value(BigInteger.ONE.shiftLeft(127),large));
        assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value(input.add(new BigDecimal("0.0000000000000000001")),decimal));
        var time=new Column("t","datetime(0)",false,null,"");assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value(LocalDateTime.of(2026,1,1,0,0,0,1),time));}
    @Test void byteBudgetsMalformedUnicodeAndNullAreCheckedBeforeSending(){var text=new Column("s","varchar(4)",false,null,"");assertEquals("😀",DorisJdbcSupport.value("😀",text));assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value("😀x",text));assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value("\ud800",text));assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.value(null,text));
        var table=parse(ddl("UNIQUE"));Object[] row={1,2L,new BigDecimal("1.000000000000000000"),"中文",null};var rows=new ArrayList<Object[]>();rows.add(row);var result=DorisJdbcSupport.convert(rows,List.of("part","id","amount","note","at"),table,BatchLimits.defaults());assertNotSame(row,result.getFirst());assertSame(row,rows.getFirst());assertEquals("中文",row[3]);
        assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.convert(Arrays.asList(row,row),List.of("part","id","amount","note","at"),table,new BatchLimits(1,10000,1000,99,1000)));}
}
