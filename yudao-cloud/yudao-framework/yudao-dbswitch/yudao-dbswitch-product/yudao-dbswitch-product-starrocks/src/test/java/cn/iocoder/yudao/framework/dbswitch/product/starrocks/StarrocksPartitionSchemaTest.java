package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** StarRocks 3.5.21 SHOW CREATE / SHOW PARTITIONS renderings captured from the real FE; no database is used here. */
class StarrocksPartitionSchemaTest {
    static final Map<String,String> TYPES=Map.of("k","int","c","varchar(40)","d","date","id","bigint","t","datetime","big","largeint","tiny","tinyint");
    static OlapPartitioning parse(String clause){return OlapPartitioning.parsePrefix(clause,0,TYPES::get,true).partitioning();}

    @Test void quotedNullIsANullValueOnlyForTypedListColumns() {
        // Probe table ln/dn: VALUES IN (NULL) is rendered 'NULL', and only NULL keys are routed there.
        var typed=parse("PARTITION BY LIST(`k`)(\n  PARTITION pn VALUES IN ('NULL'),\n  PARTITION p1 VALUES IN ('1')\n)\nDISTRIBUTED BY HASH(`id`) BUCKETS 1 ");
        assertEquals(Collections.singletonList(Collections.singletonList(null)),typed.partitions().get(1).values());
        assertEquals("pn",typed.partitionOf(Collections.singletonList(null),TYPES::get));assertEquals("p1",typed.partitionOf(List.of(1),TYPES::get));
        assertTrue(typed.ddl(StarrocksJdbcSupport::quote).contains("PARTITION pn VALUES IN (NULL)"),"Recreated as the NULL keyword, never the text 'NULL'");
        var date=parse("PARTITION BY LIST(`d`)(\n  PARTITION p1 VALUES IN ('2026-01-01'),\n  PARTITION pn VALUES IN ('NULL')\n)\nDISTRIBUTED");
        assertEquals("pn",date.partitionOf(Collections.singletonList(null),TYPES::get));
        var multi=parse("PARTITION BY LIST(`k`,`d`)(\n  PARTITION p1 VALUES IN (('1', '2026-01-01')),\n  PARTITION pn VALUES IN (('NULL', '2026-01-01'), ('2', 'NULL'))\n)\nDISTRIBUTED");
        assertEquals("pn",multi.partitionOf(Arrays.asList(2,null),TYPES::get));assertEquals("pn",multi.partitionOf(Arrays.asList(null,LocalDate.of(2026,1,1)),TYPES::get));
        // Probe tables vs/vn: on a text column 'NULL' is either the NULL partition or the text; the rendering cannot tell.
        assertThrows(IllegalArgumentException.class,()->parse("PARTITION BY LIST(`c`)(\n  PARTITION ps VALUES IN ('NULL'),\n  PARTITION pa VALUES IN ('a')\n)\nDISTRIBUTED"));
        assertThrows(IllegalArgumentException.class,()->parse("PARTITION BY LIST(`k`,`c`)(\n  PARTITION pn VALUES IN (('2', 'NULL'))\n)\nDISTRIBUTED"));
        assertEquals(List.of(List.of("null")),parse("PARTITION BY LIST(`c`)(\n  PARTITION p VALUES IN ('null')\n)\nDISTRIBUTED").partitions().getFirst().values(),"Only the exact vendor rendering is ambiguous");
    }
    /** Probe table forge: the single value a', 'b is rendered ('a', 'b'); SHOW PARTITIONS keeps it as one escaped JSON string. */
    @Test void forgedListLiteralsAreDetectedByTheExactJsonWitness() {
        var forged=parse("PARTITION BY LIST(`c`)(\n  PARTITION p1 VALUES IN ('a', 'b')\n)\nDISTRIBUTED BY HASH(`id`) BUCKETS 1 ");
        assertEquals(List.of(List.of("a"),List.of("b")),forged.partitions().getFirst().values());
        assertEquals(List.of(List.of("a', 'b")),StarrocksJdbcSupport.listWitness("[[\"a\\u0027, \\u0027b\"]]"));
        assertFalse(forged.witnessed("p1",StarrocksJdbcSupport.listWitness("[[\"a\\u0027, \\u0027b\"]]"),TYPES::get));
        assertTrue(forged.witnessed("p1",StarrocksJdbcSupport.listWitness("[[\"b\"],[\"a\"]]"),TYPES::get));
        assertEquals(List.of(List.of("a\"b"),List.of("c, d")),StarrocksJdbcSupport.listWitness("[[\"a\\\"b\"],[\"c, d\"]]"),"Probe table lsp renders exact escaped JSON");
        var multi=parse("PARTITION BY LIST(`id`,`d`,`c`)(\n  PARTITION p1 VALUES IN (('1', '2026-01-01', 'a'), ('2', '2026-01-02', 'b'))\n)\nDISTRIBUTED");
        assertTrue(multi.witnessed("p1",StarrocksJdbcSupport.listWitness("[[\"1\",\"2026-01-01\",\"a\"],[\"2\",\"2026-01-02\",\"b\"]]"),TYPES::get));
        assertFalse(multi.witnessed("p1",StarrocksJdbcSupport.listWitness("[[\"1\",\"2026-01-01\",\"a\"]]"),TYPES::get));
        var nulls=parse("PARTITION BY LIST(`k`)(\n  PARTITION pn VALUES IN ('NULL')\n)\nDISTRIBUTED");
        assertTrue(nulls.witnessed("pn",StarrocksJdbcSupport.listWitness("[[\"NULL\"]]"),TYPES::get));
        for(String malformed:List.of("","[[1]]","[\"a\"]","[[\"a\"]] trailing","[[null]]","{\"a\":1}"))assertThrows(IllegalArgumentException.class,()->StarrocksJdbcSupport.listWitness(malformed));
    }
    @Test void rangeRenderingsAndTheTypedKeysWitnessAgree() {
        // Probe tables r1/rdt: fixed ranges, the implicit type-minimum lower bound and microsecond bounds.
        var range=parse("PARTITION BY RANGE(`d`)\n(PARTITION p1 VALUES [(\"0000-01-01\"), (\"2026-01-01\")),\nPARTITION pmax VALUES [(\"2026-01-01\"), (MAXVALUE)))\nDISTRIBUTED BY HASH(`id`) BUCKETS 2 ");
        assertTrue(range.witnessed("pmax",OlapPartitioning.keysWitness("[types: [DATE]; keys: [2026-01-01]; ..types: [DATE]; keys: [MAXVALUE]; )","RANGE"),TYPES::get));
        assertEquals("p1",range.partitionOf(Collections.singletonList(null),TYPES::get));assertEquals("pmax",range.partitionOf(List.of(LocalDate.of(9999,12,31)),TYPES::get));
        var wide=parse("PARTITION BY RANGE(`t`, `d`, `big`, `tiny`)\n(PARTITION p1 VALUES [(\"0000-01-01 00:00:00\", \"0000-01-01\", \"-170141183460469231731687303715884105728\", \"-128\"), (\"2026-01-01 00:00:00.123456\", \"2026-01-02\", \"170141183460469231731687303715884105727\", \"5\")))\nDISTRIBUTED");
        assertTrue(wide.witnessed("p1",OlapPartitioning.keysWitness("[types: [DATETIME, DATE, LARGEINT, TINYINT]; keys: [0000-01-01 00:00:00, 0000-01-01, -170141183460469231731687303715884105728, -128]; ..types: [DATETIME, DATE, LARGEINT, TINYINT]; keys: [2026-01-01 00:00:00.123456, 2026-01-02, 170141183460469231731687303715884105727, 5]; )","RANGE"),TYPES::get));
        assertEquals(List.of("2026-01-01 00:00:00.123456","2026-01-02","170141183460469231731687303715884105727","5"),wide.partitions().getFirst().values().get(1));
    }
    @ParameterizedTest @ValueSource(strings={"expression","automaticList","emptyRange","escapedQuote","escapedBackslash","comma","nullRange","overlap"})
    void unrenderableStarrocksPartitionFormsAreRejected(String form) {
        String clause=switch(form){
            case "expression"->"PARTITION BY date_trunc('month', t)\nDISTRIBUTED BY HASH(`id`) BUCKETS 1 ";
            case "automaticList"->"PARTITION BY (`c`)\nDISTRIBUTED BY HASH(`id`) BUCKETS 1 ";
            case "emptyRange"->"PARTITION BY RANGE(`d`)\n()\nDISTRIBUTED BY HASH(`id`) BUCKETS 1 ";
            case "escapedQuote"->"PARTITION BY LIST(`c`)(\n  PARTITION p4 VALUES IN ('q'r')\n)\nDISTRIBUTED";
            case "escapedBackslash"->"PARTITION BY LIST(`c`)(\n  PARTITION p2 VALUES IN ('e', 'g\\h')\n)\nDISTRIBUTED";
            case "comma"->"PARTITION BY LIST(`c`)(\n  PARTITION p1 VALUES IN ('c, d')\n)\nDISTRIBUTED";
            case "nullRange"->"PARTITION BY RANGE(`k`)\n(PARTITION p1 VALUES [('NULL'), (\"10\")))\nDISTRIBUTED";
            default->"PARTITION BY RANGE(`k`)\n(PARTITION p1 VALUES [(\"1\"), (\"10\")),\nPARTITION p2 VALUES [(\"5\"), (\"20\")))\nDISTRIBUTED";
        };
        assertThrows(IllegalArgumentException.class,()->parse(clause));
    }
    @Test void tableShapeRequiresNullableColumnsForNullValuesAndKeyPartitionsForPrimaryModels() {
        var nullable=new StarrocksJdbcSupport.Column("k","int",Types.INTEGER,10,0,true,"");var required=new StarrocksJdbcSupport.Column("k","int",Types.INTEGER,10,0,false,"");
        var id=new StarrocksJdbcSupport.Column("id","bigint",Types.BIGINT,19,0,false,"");
        var nulls=parse("PARTITION BY LIST(`k`)(\n  PARTITION pn VALUES IN ('NULL')\n)\nDISTRIBUTED");
        assertDoesNotThrow(()->table("DUP_KEYS",List.of(nullable,id),List.of("k","id"),nulls));
        assertThrows(IllegalArgumentException.class,()->table("DUP_KEYS",List.of(required,id),List.of("k","id"),nulls));
        var range=parse("PARTITION BY RANGE(`k`)\n(PARTITION p1 VALUES [(\"1\"), (\"10\")))\nDISTRIBUTED");
        assertThrows(IllegalArgumentException.class,()->table("PRIMARY_KEYS",List.of(id,required),List.of("id"),range),"A primary model partitions only by key columns");
        var keyed=table("PRIMARY_KEYS",List.of(required,id),List.of("k","id"),range);
        assertThrows(IllegalArgumentException.class,()->StarrocksJdbcSupport.convert(List.<Object[]>of(new Object[]{10,1L}),List.of("k","id"),keyed,BatchLimits.defaults()),"Out-of-range rows fail before any connection exists");
        assertEquals(1,StarrocksJdbcSupport.convert(List.<Object[]>of(new Object[]{9,1L}),List.of("k","id"),keyed,BatchLimits.defaults()).size());
    }
    private static StarrocksJdbcSupport.Table table(String model,List<StarrocksJdbcSupport.Column> columns,List<String> keys,OlapPartitioning partitioning){
        return new StarrocksJdbcSupport.Table("a".repeat(64),"1:2",model,columns,keys,List.of("id"),1,Map.of(),partitioning,List.of());
    }
}
