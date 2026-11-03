package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

/** Doris 4.0.8 SHOW CREATE / SHOW PARTITIONS renderings captured from the real FE; no database is used here. */
class DorisPartitionSchemaTest {
    static final String PROPERTIES="PROPERTIES (\n\"replication_allocation\" = \"tag.location.default: 1\",\n\"storage_format\" = \"V2\"%s\n);";
    static final String RANGE="CREATE TABLE `r1` (\n  `k` date NOT NULL,\n  `id` bigint NOT NULL,\n  `v` varchar(20) NULL\n) ENGINE=OLAP\nUNIQUE KEY(`k`, `id`)\nPARTITION BY RANGE(`k`)\n(PARTITION p1 VALUES [('0000-01-01'), ('2026-01-01')),\nPARTITION p2 VALUES [('2026-01-01'), ('2026-02-01')),\nPARTITION pmax VALUES [('2026-02-01'), (MAXVALUE)))\nDISTRIBUTED BY HASH(`id`) BUCKETS 2\n"+PROPERTIES.formatted(",\n\"enable_unique_key_merge_on_write\" = \"true\"");
    static final String MULTI="CREATE TABLE `r2` (\n  `a` int NOT NULL,\n  `b` datetime(3) NOT NULL,\n  `c` largeint NOT NULL,\n  `v` decimal(10,2) NULL\n) ENGINE=OLAP\nUNIQUE KEY(`a`, `b`, `c`)\nPARTITION BY RANGE(`a`, `b`)\n(PARTITION p1 VALUES [(\"-2147483648\", '0000-01-01 00:00:00.000'), (\"10\", '2026-01-01 00:00:00')),\nPARTITION p2 VALUES [(\"10\", '2026-01-01 00:00:00'), (\"20\", '2026-01-01 12:00:00.123')),\nPARTITION p3 VALUES [(\"20\", '2026-01-01 12:00:00.123'), (MAXVALUE, MAXVALUE)))\nDISTRIBUTED BY HASH(`a`) BUCKETS AUTO\n"+PROPERTIES.formatted(",\n\"enable_unique_key_merge_on_write\" = \"false\"");
    static final String LIST="CREATE TABLE `l2` (\n  `id` int NOT NULL,\n  `city` varchar(20) NOT NULL,\n  `d` date NOT NULL,\n  `v` int NULL\n) ENGINE=OLAP\nDUPLICATE KEY(`id`, `city`, `d`)\nPARTITION BY LIST (`id`, `city`)\n(PARTITION p1 VALUES IN ((\"1\", \"beijing\"),(\"1\", \"shanghai\")),\nPARTITION p2 VALUES IN ((\"2\", \"beijing\")))\nDISTRIBUTED BY RANDOM BUCKETS 3\n"+PROPERTIES.formatted("");
    static final String AGGREGATE="CREATE TABLE `agg` (\n  `k` int NOT NULL,\n  `s` bigint SUM NULL DEFAULT \"0\",\n  `mx` int MAX NULL,\n  `mn` int MIN NULL,\n  `r` varchar(10) REPLACE NULL,\n  `rn` varchar(10) REPLACE_IF_NOT_NULL NULL\n) ENGINE=OLAP\nAGGREGATE KEY(`k`)\nPARTITION BY LIST (`k`)\n(PARTITION p_one VALUES IN (\"1\",\"2\"))\nDISTRIBUTED BY HASH(`k`) BUCKETS 1\n"+PROPERTIES.formatted("");
    static final String INDEXED="CREATE TABLE `idx` (\n  `k` int NOT NULL,\n  `t` varchar(100) NULL,\n  `u` text NULL,\n  INDEX idx_t (`t`) USING INVERTED PROPERTIES(\"lower_case\" = \"true\", \"parser\" = \"english\", \"support_phrase\" = \"true\") COMMENT \"inv\",\n  INDEX idx_u (`u`) USING NGRAM_BF PROPERTIES(\"bf_size\" = \"256\", \"gram_size\" = \"3\")\n) ENGINE=OLAP\nDUPLICATE KEY(`k`)\nDISTRIBUTED BY RANDOM BUCKETS AUTO\n"+PROPERTIES.formatted(",\n\"bloom_filter_columns\" = \"t, k\"");
    /** ln2 of the probe: LIST(k INT NULL, c VARCHAR NULL) with NULL components rendered as the unquoted keyword. */
    static final String NULLS="CREATE TABLE `ln2` (\n  `k` int NULL,\n  `c` varchar(10) NULL,\n  `id` int NOT NULL\n) ENGINE=OLAP\nDUPLICATE KEY(`k`, `c`, `id`)\nPARTITION BY LIST (`k`, `c`)\n(PARTITION pn VALUES IN ((NULL, \"a\"),(NULL, NULL),(\"2\", NULL)),\nPARTITION p1 VALUES IN ((\"1\", \"a\")))\nDISTRIBUTED BY HASH(`id`) BUCKETS 1\n"+PROPERTIES.formatted("");
    /** lstr of the probe: a NULL value and the text 'NULL' are distinct partitions. */
    static final String TEXT_NULL="CREATE TABLE `lstr` (\n  `c` varchar(40) NULL,\n  `id` int NOT NULL\n) ENGINE=OLAP\nDUPLICATE KEY(`c`, `id`)\nPARTITION BY LIST (`c`)\n(PARTITION p2 VALUES IN (NULL),\nPARTITION p3 VALUES IN (\"\"),\nPARTITION p1 VALUES IN (\"NULL\"))\nDISTRIBUTED BY HASH(`id`) BUCKETS 1\n"+PROPERTIES.formatted("");

    @Test void rangeListRandomAutoAggregateAndIndexesRoundTripAsStructuredValues() {
        var range=parse(RANGE);assertEquals("RANGE",range.partitioning().kind());assertEquals(List.of("p1","p2","pmax"),range.partitioning().partitions().stream().map(OlapPartitioning.Partition::name).toList());
        assertEquals(List.of(List.of("2026-02-01"),List.of(OlapPartitioning.MAXVALUE)),range.partitioning().partitions().get(2).values());assertEquals(2,range.buckets());assertEquals(List.of("id"),range.distribution());
        var multi=parse(MULTI);assertEquals(0,multi.buckets());assertEquals(List.of("a","b"),multi.partitioning().columns());assertEquals(List.of(List.of("10","2026-01-01 00:00:00"),List.of("20","2026-01-01 12:00:00.123")),multi.partitioning().partitions().get(1).values());
        var list=parse(LIST);assertEquals(List.of(),list.distribution());assertEquals(3,list.buckets());assertEquals(List.of(List.of("1","beijing"),List.of("1","shanghai")),list.partitioning().partitions().getFirst().values());
        var aggregate=parse(AGGREGATE);assertFalse(aggregate.unique());assertEquals("SUM",aggregate.column("s").aggregation());assertEquals("REPLACE_IF_NOT_NULL",aggregate.column("rn").aggregation());assertNull(aggregate.column("k").aggregation());assertEquals("'0'",aggregate.column("s").defaultSql());
        var indexed=parse(INDEXED);assertEquals(List.of("idx_t","idx_u"),indexed.indexes().stream().map(Index::name).toList());assertEquals("english",indexed.indexes().getFirst().properties().get("parser"));assertEquals("inv",indexed.indexes().getFirst().comment());
        assertEquals("k, t",indexed.properties().get("bloom_filter_columns"),"Rendered hash order is normalized to column order");
        for(var table:List.of(range,multi,list,aggregate,indexed,parse(NULLS),parse(TEXT_NULL)))assertEquals(table,parse(table.ddl("target","copy")),()->table.ddl("target","copy"));
        assertTrue(multi.ddl("db","t").contains("PARTITION p3 VALUES [('20', '2026-01-01 12:00:00.123'), (MAXVALUE, MAXVALUE))"));
        assertTrue(list.ddl("db","t").contains("DISTRIBUTED BY RANDOM BUCKETS 3"));assertTrue(multi.ddl("db","t").contains("DISTRIBUTED BY HASH(`a`) BUCKETS AUTO"));
    }
    @Test void nullListValuesAreRenderedAsTheKeywordAndRouteOnlyNullKeys() {
        var nulls=parse(NULLS);var pn=nulls.partitioning().partitions().stream().filter(p->p.name().equals("pn")).findFirst().orElseThrow();
        assertEquals(Arrays.asList(Arrays.asList(null,null),Arrays.asList(null,"a"),Arrays.asList("2",null)),pn.values(),"NULL sorts first; tuples are canonical");
        assertTrue(nulls.ddl("db","t").contains("PARTITION pn VALUES IN ((NULL, NULL), (NULL, 'a'), ('2', NULL))"),()->nulls.ddl("db","t"));
        assertEquals("pn",route(nulls,null,"a"));assertEquals("pn",route(nulls,null,null));assertEquals("pn",route(nulls,2,null));assertEquals("p1",route(nulls,1,"a"));
        assertThrows(IllegalArgumentException.class,()->route(nulls,1,null),"A NULL component only matches a NULL partition value");
        var text=parse(TEXT_NULL);assertEquals("p2",route(text,(Object)null));assertEquals("p1",route(text,"NULL"));assertEquals("p3",route(text,""));
        assertTrue(text.ddl("db","t").contains("PARTITION p1 VALUES IN ('NULL'), PARTITION p2 VALUES IN (NULL), PARTITION p3 VALUES IN ('')"),()->text.ddl("db","t"));
    }
    @Test void equivalentVendorLiteralRenderingsCompareAsTheSameBounds() {
        assertEquals(parse(MULTI),parse(MULTI.replace("'0000-01-01 00:00:00.000'","'0000-01-01 00:00:00'").replace("(\"10\",","('10',")));
        var starrocks=OlapPartitioning.parsePrefix("PARTITION BY RANGE(`a`, `b`)\n(PARTITION p1 VALUES [(\"-2147483648\", \"0000-01-01 00:00:00\"), (\"10\", \"2026-01-01 00:00:00.500000\")))\nDISTRIBUTED",0,name->name.equals("a")?"int":"datetime");
        var doris=OlapPartitioning.parsePrefix("PARTITION BY RANGE(`a`, `b`) (PARTITION p1 VALUES [(\"-2147483648\", '0000-01-01 00:00:00'), (\"10\", '2026-01-01 00:00:00.5')))",0,name->name.equals("a")?"int":"datetime(3)");
        assertEquals(doris.partitioning(),starrocks.partitioning());
    }
    /** "a","b" in SHOW CREATE is either two values or the single value a","b; only the SHOW PARTITIONS witness tells them apart. */
    @Test void forgedLiteralsAreDetectedByTheIndependentShowPartitionsWitness() {
        var forged=OlapPartitioning.parsePrefix("PARTITION BY LIST (`c`)\n(PARTITION p1 VALUES IN (\"a\",\"b\"))\nDISTRIBUTED",0,name->"varchar(40)").partitioning();
        assertEquals(List.of(List.of("a"),List.of("b")),forged.partitions().getFirst().values());
        assertFalse(forged.witnessed("p1",OlapPartitioning.keysWitness("[types: [VARCHAR]; keys: [a\",\"b]; ]","LIST"),name->"varchar(40)"),"Probe table forge: one value a\",\"b");
        assertTrue(forged.witnessed("p1",OlapPartitioning.keysWitness("[types: [VARCHAR]; keys: [a]; , types: [VARCHAR]; keys: [b]; ]","LIST"),name->"varchar(40)"));
        var multi=OlapPartitioning.parsePrefix("PARTITION BY LIST (`k`, `c`)\n(PARTITION p1 VALUES IN ((\"1\", \"a\"),(\"2\", \"b\")))\nDISTRIBUTED",0,name->name.equals("k")?"int":"varchar(9)").partitioning();
        assertFalse(multi.witnessed("p1",OlapPartitioning.keysWitness("[types: [INT, VARCHAR]; keys: [1, a\"),(\"2\", \"b]; ]","LIST"),name->name.equals("k")?"int":"varchar(9)"),"Same-structure forgery in a multi-column tuple");
        assertTrue(multi.witnessed("p1",OlapPartitioning.keysWitness("[types: [INT, VARCHAR]; keys: [2, b]; , types: [INT, VARCHAR]; keys: [1, a]; ]","LIST"),name->name.equals("k")?"int":"varchar(9)"),"Witness order is not significant");
        var nulls=parse(NULLS).partitioning();Map<String,String> types=Map.of("k","int","c","varchar(10)");
        assertTrue(nulls.witnessed("pn",OlapPartitioning.keysWitness("[types: [INT, VARCHAR]; keys: [NULL, a]; , types: [INT, VARCHAR]; keys: [NULL, NULL]; , types: [INT, VARCHAR]; keys: [2, NULL]; ]","LIST"),types::get));
        assertFalse(nulls.witnessed("pn",OlapPartitioning.keysWitness("[types: [INT, VARCHAR]; keys: [NULL, a]; , types: [INT, VARCHAR]; keys: [2, NULL]; ]","LIST"),types::get));
        var text=parse(TEXT_NULL).partitioning();
        assertTrue(text.witnessed("p3",OlapPartitioning.keysWitness("[types: [VARCHAR]; keys: []; ]","LIST"),name->"varchar(40)"),"Empty text has an empty key");
        assertTrue(text.witnessed("p1",OlapPartitioning.keysWitness("[types: [VARCHAR]; keys: [NULL]; ]","LIST"),name->"varchar(40)"));
        var range=OlapPartitioning.parsePrefix("PARTITION BY RANGE(`k`, `d`, `id`)\n(PARTITION p1 VALUES [('0000-01-01 00:00:00.000000', '0000-01-01', \"-170141183460469231731687303715884105728\"), ('2026-01-01 00:00:00.123456', '2026-01-02', \"170141183460469231731687303715884105727\")),\nPARTITION p2 VALUES [('2026-01-01 00:00:00.123456', '2026-01-02', \"170141183460469231731687303715884105727\"), (MAXVALUE, MAXVALUE, MAXVALUE)))\nDISTRIBUTED",0,
                name->Map.of("k","datetime(6)","d","date","id","largeint").get(name)).partitioning();
        assertTrue(range.witnessed("p2",OlapPartitioning.keysWitness("[types: [DATETIMEV2, DATEV2, LARGEINT]; keys: [2026-01-01 00:00:00.123456, 2026-01-02, 170141183460469231731687303715884105727]; ..types: [DATETIMEV2, DATEV2, LARGEINT]; keys: [MAXVALUE, MAXVALUE, MAXVALUE]; )","RANGE"),
                name->Map.of("k","datetime(6)","d","date","id","largeint").get(name)));
        assertFalse(range.witnessed("p1",OlapPartitioning.keysWitness("[types: [DATETIMEV2, DATEV2, LARGEINT]; keys: [0000-01-01 00:00:00.000000, 0000-01-01, -170141183460469231731687303715884105728]; ..types: [DATETIMEV2, DATEV2, LARGEINT]; keys: [2026-01-01 00:00:00.123457, 2026-01-02, 170141183460469231731687303715884105727]; )","RANGE"),
                name->Map.of("k","datetime(6)","d","date","id","largeint").get(name)),"One microsecond of bound drift is a different partition");
        for(String malformed:List.of("","[]","[types: [VARCHAR]; keys: [a]; ","[types: [VARCHAR]; keys: [a]; ..types: [VARCHAR]; keys: [b]; )"))assertThrows(IllegalArgumentException.class,()->OlapPartitioning.keysWitness(malformed,"LIST"));
    }
    @ParameterizedTest @ValueSource(strings={"dynamic","auto","empty","comma","nullRange","nullNotNull","doubledQuote","backslash","hll","bitmapIndex","charFilter","hyphenName","booleanList","rangeOnText","overflow","overlapOrder","partitionValueColumn","duplicateAcrossPartitions"})
    void unverifiedPartitionIndexAndAggregateFormsAreRejectedInsteadOfBecomingAHeap(String form) {
        String ddl=switch(form){
            case "dynamic"->RANGE.replace("\"storage_format\" = \"V2\"","\"storage_format\" = \"V2\",\n\"dynamic_partition.enable\" = \"true\"");
            case "auto"->"CREATE TABLE `a` (\n  `k` datetime NOT NULL,\n  `id` int NOT NULL\n) ENGINE=OLAP\nDUPLICATE KEY(`k`, `id`)\nAUTO PARTITION BY RANGE (date_trunc(`k`, 'month'))\n()\nDISTRIBUTED BY HASH(`id`) BUCKETS 1\n"+PROPERTIES.formatted("");
            case "empty"->RANGE.substring(0,RANGE.indexOf("(PARTITION p1"))+"()\nDISTRIBUTED BY HASH(`id`) BUCKETS 2\n"+PROPERTIES.formatted("");
            case "comma"->LIST.replace("\"shanghai\"","\"c, d\"");
            case "nullRange"->RANGE.replace("[('2026-01-01'), ('2026-02-01'))","[(NULL), ('2026-02-01'))");
            case "nullNotNull"->LIST.replace("((\"2\", \"beijing\"))","((\"2\", NULL))");
            case "doubledQuote"->LIST.replace("\"shanghai\"","\"sh''anghai\"");
            case "backslash"->LIST.replace("\"shanghai\"","\"sh\\\\anghai\"");
            case "hll"->AGGREGATE.replace("REPLACE_IF_NOT_NULL","HLL_UNION");
            case "bitmapIndex"->INDEXED.replace("USING NGRAM_BF","USING BITMAP");
            case "charFilter"->INDEXED.replace("\"lower_case\" = \"true\"","\"char_filter_pattern\" = \"x\", \"char_filter_type\" = \"char_replace\"");
            case "hyphenName"->RANGE.replace("PARTITION p2 VALUES","PARTITION p-2 VALUES");
            case "booleanList"->"CREATE TABLE `lst` (\n  `b` boolean NOT NULL,\n  `id` int NOT NULL\n) ENGINE=OLAP\nDUPLICATE KEY(`b`, `id`)\nPARTITION BY LIST (`b`)\n(PARTITION p1 VALUES IN (\"true\"))\nDISTRIBUTED BY HASH(`id`) BUCKETS 1\n"+PROPERTIES.formatted("");
            case "rangeOnText"->RANGE.replace("PARTITION BY RANGE(`k`)","PARTITION BY RANGE(`v`)");
            case "overflow"->MULTI.replace("\"-2147483648\"","\"-2147483649\"");
            case "overlapOrder"->RANGE.replace("[('2026-01-01'), ('2026-02-01'))","[('2026-02-01'), ('2026-01-01'))");
            case "duplicateAcrossPartitions"->LIST.replace("((\"2\", \"beijing\"))","((\"1\", \"beijing\"))");
            default->LIST.replace("DUPLICATE KEY(`id`, `city`, `d`)","UNIQUE KEY(`id`, `city`, `d`)").replace("PARTITION BY LIST (`id`, `city`)","PARTITION BY LIST (`id`, `v`)").replace("DISTRIBUTED BY RANDOM","DISTRIBUTED BY HASH(`id`)");
        };
        assertThrows(IllegalArgumentException.class,()->parse(ddl));
    }
    @Test void routingHonorsInclusiveLowerExclusiveUpperMaxvalueMultiColumnListAndNullMinimum() {
        var range=parse(RANGE);
        assertEquals("p1",route(range,LocalDate.of(2025,12,31)));assertEquals("p2",route(range,LocalDate.of(2026,1,1)));assertEquals("p2",route(range,LocalDate.of(2026,1,31)));
        assertEquals("pmax",route(range,LocalDate.of(2026,2,1)));assertEquals("pmax",route(range,LocalDate.of(9999,12,31)));assertEquals("p1",route(range,(Object)null),"NULL sorts as the domain minimum");
        var bounded=parse(RANGE.replace(",\nPARTITION pmax VALUES [('2026-02-01'), (MAXVALUE))",""));
        assertThrows(IllegalArgumentException.class,()->route(bounded,LocalDate.of(2026,2,1)));
        var shifted=parse(RANGE.replace("('0000-01-01')","('2020-01-01')"));assertThrows(IllegalArgumentException.class,()->route(shifted,(Object)null),"A NULL key has no partition above the minimum");
        var multi=parse(MULTI);LocalDateTime noon=LocalDateTime.of(2026,1,1,12,0);
        assertEquals("p1",route(multi,9,LocalDateTime.of(9999,1,1,0,0)));assertEquals("p1",route(multi,10,LocalDateTime.of(2025,12,31,23,59,59,999000000)));
        assertEquals("p2",route(multi,10,LocalDateTime.of(2026,1,1,0,0)));assertEquals("p2",route(multi,20,noon.withNano(122000000)));assertEquals("p3",route(multi,20,noon.withNano(123000000)));assertEquals("p3",route(multi,Integer.MAX_VALUE,noon));
        var list=parse(LIST);assertEquals("p1",route(list,1,"shanghai"));assertEquals("p2",route(list,2L,"beijing"));
        assertThrows(IllegalArgumentException.class,()->route(list,2,"shanghai"));assertThrows(IllegalArgumentException.class,()->route(list,null,"beijing"));
        assertThrows(IllegalArgumentException.class,()->route(list,new BigDecimal("1.5"),"beijing"));assertThrows(IllegalArgumentException.class,()->route(range,"2026-01-01"),"Text is not a verified DATE value");
    }
    @Test void mappingRenamesPartitionIndexAndBloomColumnsOrRejectsTheirLoss() {
        var mapped=parse(INDEXED.replace("DUPLICATE KEY(`k`)\n","DUPLICATE KEY(`k`)\nPARTITION BY RANGE(`k`)\n(PARTITION p1 VALUES [(\"-2147483648\"), (\"100\")))\n")).map(Map.of("k","key","t","text","u","ngram"));
        assertEquals(List.of("key"),mapped.partitioning().columns());assertEquals(List.of("text"),mapped.indexes().getFirst().columns());assertEquals("key, text",mapped.properties().get("bloom_filter_columns"));
        assertEquals(mapped,parse(mapped.ddl("db","renamed")));
        assertThrows(IllegalArgumentException.class,()->parse(INDEXED).map(Map.of("k","key","u","ngram")),"An indexed or bloom filter column cannot disappear");
        assertThrows(IllegalArgumentException.class,()->parse(RANGE).map(Map.of("id","id2","v","v")),"Partition and key columns must be retained");
    }
    @Test void outOfPartitionBatchIsRejectedBeforeAnyConnectionOnTheNativeWritePath()throws Exception {
        var table=parse(RANGE.replace(",\nPARTITION pmax VALUES [('2026-02-01'), (MAXVALUE))",""));var source=mock(DataSource.class);
        var rows=List.of(new Object[]{LocalDate.of(2026,1,15),1L,"ok"},new Object[]{LocalDate.of(2026,3,1),2L,"outside"});
        var evidence=new DorisJdbcSupport.Evidence(table,"a".repeat(64),"1:2:3");
        var failure=assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.write(source,"db","r1",evidence,List.of("k","id","v"),rows,DorisJdbcSupport.Operation.INSERT,5,BatchLimits.defaults()));
        assertFalse(failure.getMessage().contains("2026-03-01"),"Rejected values must not escape through the message");verify(source,never()).getConnection();
        assertThrows(IllegalArgumentException.class,()->DorisJdbcSupport.convert(List.<Object[]>of(new Object[]{1L,"ok"}),List.of("id","v"),table,BatchLimits.defaults()),"Partition columns must be written");
        assertEquals(1,DorisJdbcSupport.convert(List.<Object[]>of(new Object[]{java.sql.Date.valueOf("2026-01-31"),3L,null}),List.of("k","id","v"),table,BatchLimits.defaults()).size());
    }
    private static String route(Table table,Object...values){return table.partitioning().partitionOf(Arrays.asList(values),name->table.column(name).type());}
}
