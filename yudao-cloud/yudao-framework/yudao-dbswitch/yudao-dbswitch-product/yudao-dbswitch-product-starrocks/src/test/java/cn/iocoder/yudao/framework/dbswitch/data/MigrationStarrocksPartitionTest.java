package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.starrocks.StarrocksDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public MigrationService/JDBC/Stream Load paths on real StarRocks 3.5.21 static RANGE/LIST partitions, proven separately from Doris. */
@Timeout(300)
@EnabledIfSystemProperty(named="datahub.test.starrocks.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationStarrocksPartitionTest {
    static final String RANGE="(k DATE NOT NULL,id BIGINT NOT NULL,amount DECIMAL(38,18),big LARGEINT,at DATETIME,note VARCHAR(200) COMMENT 'n''q',payload VARBINARY,INDEX idx_note (note) USING BITMAP COMMENT 'bm')"
            +" PRIMARY KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('2026-01-01'),PARTITION p2 VALUES LESS THAN ('2026-02-01'),PARTITION pmax VALUES LESS THAN (MAXVALUE))"
            +" DISTRIBUTED BY HASH(id) BUCKETS 2 PROPERTIES('replication_num'='1','bloom_filter_columns'='id,note')";
    static final Map<String,String> SHAPES=Map.of(
            "range",RANGE,
            "fixed","(a INT NOT NULL,b DATETIME NOT NULL,v VARCHAR(20)) DUPLICATE KEY(a,b) PARTITION BY RANGE(a,b) (PARTITION p1 VALUES [('-2147483648','0000-01-01 00:00:00'),('10','2026-01-01 00:00:00.5')),PARTITION p2 VALUES [('10','2026-01-01 00:00:00.5'),('2147483647','9999-12-31 23:59:59'))) DISTRIBUTED BY RANDOM PROPERTIES('replication_num'='1')",
            "list","(city VARCHAR(20) NOT NULL,id INT NOT NULL,v VARCHAR(20)) PRIMARY KEY(city,id) PARTITION BY LIST(city) (PARTITION p_bj VALUES IN ('beijing','中文'),PARTITION p_empty VALUES IN (''),PARTITION p_sh VALUES IN ('shanghai')) DISTRIBUTED BY HASH(city) PROPERTIES('replication_num'='1')",
            "listMulti","(id INT NOT NULL,d DATE NOT NULL,city VARCHAR(20) NOT NULL,v INT) DUPLICATE KEY(id,d,city) PARTITION BY LIST(id,d,city) (PARTITION p1 VALUES IN (('1','2026-01-01','a'),('2','2026-01-02','b')),PARTITION p2 VALUES IN (('3','2026-01-03','c'))) DISTRIBUTED BY RANDOM BUCKETS 3 PROPERTIES('replication_num'='1')",
            "nullList","(k INT NULL,id INT NOT NULL,v VARCHAR(10)) DUPLICATE KEY(k,id) PARTITION BY LIST(k) (PARTITION p1 VALUES IN ('1','2'),PARTITION pn VALUES IN (NULL)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
    static final Map<String,String> SEEDS=Map.of(
            "range","('2025-12-31',1,12345678901234567890.123456789012345678,170141183460469231731687303715884105727,'2026-10-03 12:34:56.123456','中文''\\\\',x'0002ff'),('2026-01-01',2,-0.000000000000000001,-170141183460469231731687303715884105728,'0001-01-01 00:00:00.000001',NULL,x'61'),('2026-01-31',3,NULL,NULL,NULL,'x',NULL),('2026-02-01',4,0,0,'9999-12-31 23:59:59.999999','max',x'00'),('9999-12-31',5,1,1,'2026-01-01 00:00:00','last',x'ff')",
            "fixed","(9,'9999-01-01 00:00:00','x'),(10,'2026-01-01 00:00:00.499999','below'),(10,'2026-01-01 00:00:00.500000','at'),(2147483647,'2026-01-01 00:00:00','max')",
            "list","('beijing',1,'a'),('中文',2,NULL),('',3,''),('shanghai',4,'d')",
            "listMulti","(1,'2026-01-01','a',1),(1,'2026-01-01','a',1),(2,'2026-01-02','b',NULL),(3,'2026-01-03','c',3)",
            "nullList","(1,1,'a'),(NULL,2,'n'),(2,3,NULL),(NULL,4,'')");
    static final Map<String,Map<String,Long>> COUNTS=Map.of("range",Map.of("p1",1L,"p2",2L,"pmax",2L),"fixed",Map.of("p1",2L,"p2",2L),"list",Map.of("p_bj",2L,"p_empty",1L,"p_sh",1L),
            "listMulti",Map.of("p1",3L,"p2",1L),"nullList",Map.of("p1",2L,"pn",2L));
    static final String PG_TABLE="(k date NOT NULL,id bigint NOT NULL,amount numeric(38,18),big numeric(39,0),at timestamp(6),note varchar(200),payload bytea,PRIMARY KEY(k,id))";
    static final String PG_SEED="('2025-12-31',1,12345678901234567890.123456789012345678,170141183460469231731687303715884105727,'2026-10-03 12:34:56.123456','中文''\\',decode('0002ff','hex')),('2026-01-01',2,-0.000000000000000001,-170141183460469231731687303715884105728,'0001-01-01 00:00:00.000001',NULL,decode('61','hex')),('2026-01-31',3,NULL,NULL,NULL,'x',NULL),('2026-02-01',4,0,0,'9999-12-31 23:59:59.999999','max',decode('00','hex')),('9999-12-31',5,1,1,'2026-01-01 00:00:00','last',decode('ff','hex'))";
    static final String COLUMNS="k,id,amount,big,at,note,payload";
    private Connection control;private String schema;private final List<com.zaxxer.hikari.HikariDataSource> pools=new ArrayList<>();
    @BeforeEach void setup()throws Exception {
        control=DriverManager.getConnection(url(),"root","");
        try(var s=control.createStatement();var rows=s.executeQuery("SELECT current_version()")){assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("3.5.21"));}
        schema="partition_"+UUID.randomUUID().toString().replace("-","");sql("CREATE DATABASE "+schema);
    }
    @AfterEach void cleanup()throws Exception {try{if(control!=null)try(var owned=control){if(schema!=null)sql("DROP DATABASE "+schema+" FORCE");}}finally{pools.forEach(com.zaxxer.hikari.HikariDataSource::close);}}

    @ParameterizedTest @CsvSource({"range,false","range,true","fixed,true","list,false","listMulti,true","nullList,false","nullList,true"})
    void nativePartitionedStructureAndRowsRoundTripThroughAutoCreatedFullMigration(String shape,boolean streamLoad)throws Exception {
        create("source_rows",shape);sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get(shape));
        success(run("source_rows","created_rows",false,streamLoad));
        assertEquals(nativeShape("source_rows"),nativeShape("created_rows"));assertNotNull(metadata("created_rows").partitioning());
        assertEquals(COUNTS.get(shape),partitions("source_rows"));assertEquals(COUNTS.get(shape),partitions("created_rows"));
        assertEquals(normalized(control,"SELECT * FROM "+table("source_rows")),normalized(control,"SELECT * FROM "+table("created_rows")));
        if(shape.equals("range")){var created=metadata("created_rows");assertEquals("id, note",created.properties().get("bloom_filter_columns"));assertEquals(1,created.indexes().size());assertEquals(2,created.buckets());}
        if(shape.equals("nullList"))assertEquals(2L,Long.parseLong(single("SELECT COUNT(*) FROM "+table("created_rows")+" PARTITION (pn) WHERE k IS NULL")));
    }
    /** The table comment is not part of SHOW CREATE parsing; it is captured exactly and verified on the recreated table. */
    @Test void tableCommentIsCapturedExactlyAndVerifiedOnTheRecreatedTable()throws Exception {
        sql("CREATE TABLE "+table("source_rows")+SHAPES.get("list").replace("PRIMARY KEY(city,id) ","PRIMARY KEY(city,id) COMMENT 'tbl ''q'' 中文' "));sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("list"));
        assertEquals("tbl 'q' 中文",tableComment("source_rows"));
        success(run("source_rows","created_rows",false,false));assertEquals("tbl 'q' 中文",tableComment("created_rows"));assertEquals(nativeShape("source_rows"),nativeShape("created_rows"));
        var onlyCreate=crossRequest(ProductTypeEnum.STARROCKS,schema,ProductTypeEnum.STARROCKS,schema,"source_rows","structure_only",false);onlyCreate.getTarget().setOnlyCreate(true);
        success(execute(onlyCreate,false));assertEquals("tbl 'q' 中文",tableComment("structure_only"));assertEquals(0,count("structure_only"));
    }
    @ParameterizedTest @ValueSource(strings={"", "ANSI_QUOTES,NO_BACKSLASH_ESCAPES"})
    void quotedUnicodeTableAndColumnCommentsRemainDataUnderBothSqlModes(String mode)throws Exception {
        String comment="中文 'single' \"double\"\nline";
        sql("SET sql_mode='"+mode+"'");String original=single("SELECT @@sql_mode");
        try {
            String literal="'"+comment.replace("'","''")+"'";
            sql("CREATE TABLE "+table("source_rows")+"(id BIGINT NOT NULL,note VARCHAR(100) COMMENT "+literal+") PRIMARY KEY(id) COMMENT "+literal+" DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            assertEquals(comment,tableComment("source_rows"));assertEquals(comment,metadata("source_rows").columns().get(1).comment());
            var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(control,true);
            var definition=cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables.describe(ds,schema,"source_rows",30);
            cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables.plan(ds,definition,schema,"created_rows",Map.of("id","id","note","note"),false,30).execute();
            assertEquals(original,single("SELECT @@sql_mode"));assertEquals(comment,tableComment("created_rows"));
            assertEquals(comment,metadata("created_rows").columns().get(1).comment());
        } finally {sql("SET sql_mode=DEFAULT");}
    }
    /** Scheduler-managed, automatic, overridden, ambiguous or unsupported sources fail before any target DDL. */
    @ParameterizedTest @ValueSource(strings={"dynamic","expression","automaticList","bucketOverride","textNull","forgedLiteral","comma","uniqueModel","aggregateModel"})
    void unrenderableStarrocksSourcesAreRejectedBeforeTargetDdl(String form)throws Exception {
        switch(form){
            case "dynamic"->sql("CREATE TABLE "+table("source_rows")+"(k DATE NOT NULL,id INT NOT NULL) DUPLICATE KEY(k,id) PARTITION BY RANGE(k) () DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','dynamic_partition.enable'='true','dynamic_partition.time_unit'='DAY','dynamic_partition.start'='-3','dynamic_partition.end'='3','dynamic_partition.prefix'='p','dynamic_partition.buckets'='1')");
            case "expression"->{sql("CREATE TABLE "+table("source_rows")+"(k DATETIME NOT NULL,id INT NOT NULL) DUPLICATE KEY(k,id) PARTITION BY date_trunc('month', k) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");sql("INSERT INTO "+table("source_rows")+" VALUES ('2026-01-05 00:00:00',1)");}
            case "automaticList"->{sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NOT NULL,id INT NOT NULL) DUPLICATE KEY(city,id) PARTITION BY (city) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");sql("INSERT INTO "+table("source_rows")+" VALUES ('beijing',1)");}
            case "bucketOverride"->{sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NOT NULL,id INT NOT NULL) DUPLICATE KEY(city,id) PARTITION BY LIST(city) (PARTITION p1 VALUES IN ('a')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");sql("ALTER TABLE "+table("source_rows")+" ADD PARTITION p2 VALUES IN ('b') DISTRIBUTED BY HASH(id) BUCKETS 5");}
            // VALUES IN (NULL) and VALUES IN ('NULL') are different server partitions on a text column but render identically.
            case "textNull"->sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NULL,id INT NOT NULL) DUPLICATE KEY(city,id) PARTITION BY LIST(city) (PARTITION pa VALUES IN ('a'),PARTITION pn VALUES IN (NULL)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            // The single value a', 'b renders exactly like the two values 'a', 'b' in SHOW CREATE.
            case "forgedLiteral"->sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NOT NULL,id INT NOT NULL) DUPLICATE KEY(city,id) PARTITION BY LIST(city) (PARTITION p1 VALUES IN ('a'', ''b')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            case "comma"->sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NOT NULL,id INT NOT NULL) DUPLICATE KEY(city,id) PARTITION BY LIST(city) (PARTITION p1 VALUES IN ('c, d','e')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            case "uniqueModel"->sql("CREATE TABLE "+table("source_rows")+"(k INT NOT NULL,v VARCHAR(10)) UNIQUE KEY(k) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('10')) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
            default->sql("CREATE TABLE "+table("source_rows")+"(k INT NOT NULL,s BIGINT SUM,r VARCHAR(10) REPLACE) AGGREGATE KEY(k) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('10')) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        }
        var result=run("source_rows","created_rows",false,false);assertFalse(result.getIfAllSuccess().get());assertFalse(exists("created_rows"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void outOfPartitionAndUnplacedNullRowsFailBeforeTheFirstRequestOnBothTransports(boolean streamLoad)throws Exception {
        sql("CREATE TABLE "+table("bounded")+RANGE.replace(",PARTITION pmax VALUES LESS THAN (MAXVALUE)",""));
        if(streamLoad)assertThrows(IllegalStateException.class,()->writer(true).prepareWrite(schema,"bounded",List.of("k","id","note")));
        var writer=writer(streamLoad);var fields=List.of("k","id","amount","big","at","note","payload");writer.prepareWrite(schema,"bounded",fields);
        var inside=new Object[]{LocalDate.of(2026,1,31),1L,null,null,null,"inside",null};var outside=new Object[]{LocalDate.of(2026,2,1),2L,null,null,null,"outside",null};
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.of(inside,outside)));assertEquals(0,count("bounded"));
        assertEquals(1,writer.write(fields,List.<Object[]>of(inside)));assertEquals(Map.of("p1",0L,"p2",1L),partitions("bounded"));
        // NULL RANGE keys sort as the type minimum: placed when a partition starts there, rejected before sending when none does.
        sql("CREATE TABLE "+table("nullable")+"(k INT NULL,id INT NOT NULL) DUPLICATE KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('10'),PARTITION p2 VALUES LESS THAN ('20')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("CREATE TABLE "+table("shifted")+"(k INT NULL,id INT NOT NULL) DUPLICATE KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES [('5'),('10'))) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        var nullable=writer(streamLoad);nullable.prepareWrite(schema,"nullable",List.of("k","id"));assertEquals(1,nullable.write(List.of("k","id"),List.<Object[]>of(new Object[]{null,1})));assertEquals(Map.of("p1",1L,"p2",0L),partitions("nullable"));
        var shifted=writer(streamLoad);shifted.prepareWrite(schema,"shifted",List.of("k","id"));assertThrows(IllegalArgumentException.class,()->shifted.write(List.of("k","id"),List.<Object[]>of(new Object[]{null,1})));assertEquals(0,count("shifted"));
        // Defense in depth if pre-send routing were bypassed: one strict statement, no silent subset.
        sql("SET enable_insert_strict=true");assertThrows(SQLException.class,()->sql("INSERT INTO "+table("bounded")+"(k,id,note) VALUES ('2026-01-15',3,'inside'),('2026-02-01',4,'outside')"));assertEquals(1,count("bounded"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void primaryKeyDifferenceAppliesInsertUpdateDeleteAndPartitionMovesThenRepeatsAsNoChange(boolean streamLoad)throws Exception {
        create("source_rows","range");create("target_rows","range");sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("range"));
        success(run("source_rows","target_rows",false,streamLoad));assertEquals(normalized(control,"SELECT * FROM "+table("source_rows")),normalized(control,"SELECT * FROM "+table("target_rows")));
        sql("DELETE FROM "+table("source_rows")+" WHERE k='2025-12-31' AND id=1");sql("UPDATE "+table("source_rows")+" SET amount=-1.5,note='changed',payload=x'0102' WHERE k='2026-01-31' AND id=3");
        sql("DELETE FROM "+table("source_rows")+" WHERE k='9999-12-31' AND id=5");sql("INSERT INTO "+table("source_rows")+" (k,id,big,at) VALUES ('2025-06-01',5,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
        for(int pass=0;pass<2;pass++){success(run("source_rows","target_rows",true,streamLoad));
            assertEquals(normalized(control,"SELECT * FROM "+table("source_rows")),normalized(control,"SELECT * FROM "+table("target_rows")));assertEquals(Map.of("p1",1L,"p2",3L,"pmax",1L),partitions("target_rows"));}
    }
    /** TRUNCATE replaces partition generations but keeps the table; the pre-send check binds table identity and structure, not partition IDs. */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitTruncateFullReplacesRowsOfTheSamePartitionedTable(boolean streamLoad)throws Exception {
        create("source_rows","list");create("target_rows","list");sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("list"));sql("INSERT INTO "+table("target_rows")+" VALUES ('shanghai',9,'obsolete')");
        var request=crossRequest(ProductTypeEnum.STARROCKS,schema,ProductTypeEnum.STARROCKS,schema,"source_rows","target_rows",false);request.getTarget().setTargetTruncateData(true);
        success(execute(request,streamLoad));assertEquals(normalized(control,"SELECT * FROM "+table("source_rows")),normalized(control,"SELECT * FROM "+table("target_rows")));assertEquals(COUNTS.get("list"),partitions("target_rows"));
    }
    /** A primary-key INSERT silently replaces; a key that appeared after comparison must stop the DIFF insert before it is sent. */
    @Test void differenceInsertOfAKeyThatAppearedAfterComparisonIsRejectedBeforeSending()throws Exception {
        create("target_rows","range");var sync=new StarrocksDatabaseSyncImpl(new DriverManagerDataSource(url(),"root",""));var fields=List.of("k","id","note");sync.prepare(schema,"target_rows",fields,List.of("k","id"));
        sql("INSERT INTO "+table("target_rows")+"(k,id,note) VALUES ('2026-01-15',9,'concurrent')");
        assertThrows(IllegalStateException.class,()->sync.executeInsert(Collections.singletonList(new Object[]{LocalDate.of(2026,1,15),9L,"difference"})));
        assertEquals("concurrent",single("SELECT note FROM "+table("target_rows")+" WHERE id=9"));
        assertEquals(1,sync.executeInsert(Collections.singletonList(new Object[]{LocalDate.of(2026,1,16),10L,"inserted"})));assertEquals("inserted",single("SELECT note FROM "+table("target_rows")+" WHERE id=10"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.+")
    void postgresqlSourceFullAndDifferenceRouteEveryRowIntoNativeStarrocksPartitions(boolean streamLoad)throws Exception {
        String pgSchema="olap_"+UUID.randomUUID().toString().replace("-","");
        try(var pg=pg()){sql(pg,"CREATE SCHEMA "+pgSchema);try{
            sql(pg,"CREATE TABLE "+pgSchema+".source_rows"+PG_TABLE);sql(pg,"INSERT INTO "+pgSchema+".source_rows VALUES "+PG_SEED);create("target_rows","range");
            success(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.STARROCKS,schema,"target_rows",false),streamLoad));
            assertEquals(normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".source_rows"),normalized(control,"SELECT "+COLUMNS+" FROM "+table("target_rows")));assertEquals(Map.of("p1",1L,"p2",2L,"pmax",2L),partitions("target_rows"));
            sql(pg,"DELETE FROM "+pgSchema+".source_rows WHERE k='2025-12-31' AND id=1");sql(pg,"UPDATE "+pgSchema+".source_rows SET amount=-1.5,note='changed' WHERE k='2026-01-31' AND id=3");
            sql(pg,"DELETE FROM "+pgSchema+".source_rows WHERE k='9999-12-31' AND id=5");sql(pg,"INSERT INTO "+pgSchema+".source_rows(k,id,big,at) VALUES ('2025-06-01',5,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
            for(int pass=0;pass<2;pass++){success(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.STARROCKS,schema,"target_rows",true),streamLoad));
                assertEquals(normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".source_rows"),normalized(control,"SELECT "+COLUMNS+" FROM "+table("target_rows")));assertEquals(Map.of("p1",1L,"p2",3L,"pmax",1L),partitions("target_rows"));}
            sql("CREATE TABLE "+table("bounded_rows")+RANGE.replace(",PARTITION pmax VALUES LESS THAN (MAXVALUE)",""));
            assertFalse(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.STARROCKS,schema,"bounded_rows",false),streamLoad).getIfAllSuccess().get());assertEquals(0,count("bounded_rows"));
        }finally{sql(pg,"DROP SCHEMA "+pgSchema+" CASCADE");}}
    }
    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.+")
    void partitionedStarrocksSourceFullAndDifferenceIntoPostgreSql()throws Exception {
        create("source_rows","range");sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("range"));String pgSchema="olap_"+UUID.randomUUID().toString().replace("-","");
        try(var pg=pg()){sql(pg,"CREATE SCHEMA "+pgSchema);try{
            sql(pg,"CREATE TABLE "+pgSchema+".target_rows"+PG_TABLE);
            success(execute(crossRequest(ProductTypeEnum.STARROCKS,schema,ProductTypeEnum.POSTGRESQL,pgSchema,"target_rows",false),false));
            assertEquals(normalized(control,"SELECT "+COLUMNS+" FROM "+table("source_rows")),normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".target_rows"));
            sql("DELETE FROM "+table("source_rows")+" WHERE k='2025-12-31' AND id=1");sql("UPDATE "+table("source_rows")+" SET amount=-1.5,note='changed',payload=x'0102' WHERE k='2026-01-31' AND id=3");
            sql("DELETE FROM "+table("source_rows")+" WHERE k='9999-12-31' AND id=5");sql("INSERT INTO "+table("source_rows")+"(k,id,big,at) VALUES ('2025-06-01',5,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
            for(int pass=0;pass<2;pass++){success(execute(crossRequest(ProductTypeEnum.STARROCKS,schema,ProductTypeEnum.POSTGRESQL,pgSchema,"target_rows",true),false));
                assertEquals(normalized(control,"SELECT "+COLUMNS+" FROM "+table("source_rows")),normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".target_rows"));}
        }finally{sql(pg,"DROP SCHEMA "+pgSchema+" CASCADE");}}
    }

    private StarrocksWriterImpl writer(boolean streamLoad){var pool=new com.zaxxer.hikari.HikariDataSource(hikari());pools.add(pool);var writer=new StarrocksWriterImpl(pool);writer.configure(options(streamLoad));return writer;}
    private static com.zaxxer.hikari.HikariConfig hikari(){var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url());config.setUsername("root");config.setPassword("");config.setMaximumPoolSize(2);config.setMinimumIdle(0);return config;}
    private void create(String name,String shape)throws Exception{sql("CREATE TABLE "+table(name)+SHAPES.get(shape));}
    private StarrocksJdbcSupport.Table metadata(String name)throws Exception{return StarrocksJdbcSupport.metadata(control,schema,name,30);}
    private Object nativeShape(String name)throws Exception{return metadata(name).shape();}
    private String tableComment(String name)throws Exception{try(var p=control.prepareStatement("SELECT TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){p.setString(1,schema);p.setString(2,name);try(var r=p.executeQuery()){assertTrue(r.next());String comment=r.getString(1);assertFalse(r.next());return comment;}}}
    private boolean exists(String name)throws Exception{try(var p=control.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){p.setString(1,schema);p.setString(2,name);try(var r=p.executeQuery()){assertTrue(r.next());return r.getLong(1)>0;}}}
    private Map<String,Long> partitions(String name)throws Exception{var names=new ArrayList<String>();try(var s=control.createStatement();var r=s.executeQuery("SHOW PARTITIONS FROM "+table(name))){while(r.next())names.add(r.getString("PartitionName"));}
        var counts=new TreeMap<String,Long>();for(String partition:names)counts.put(partition,Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)+" PARTITION ("+partition+")")));return counts;}
    /** Product-neutral values: exact decimals without scale padding, timestamps as LocalDateTime text, binary as hex (Connector/J types StarRocks VARBINARY as text), everything else as text. */
    static List<List<String>> normalized(Connection c,String query)throws SQLException{var result=new ArrayList<List<String>>();try(var s=c.createStatement();var r=s.executeQuery(query)){var m=r.getMetaData();int width=m.getColumnCount();while(r.next()){var row=new ArrayList<String>();
        for(int i=1;i<=width;i++){int type=m.getColumnType(i);String name=m.getColumnTypeName(i).toUpperCase(Locale.ROOT);
            if(type==Types.TIMESTAMP){Timestamp time=r.getTimestamp(i);row.add(time==null?null:time.toLocalDateTime().toString());}
            else if(type==Types.BINARY||type==Types.VARBINARY||type==Types.LONGVARBINARY||name.contains("BINARY")||name.equals("BYTEA")||m.getColumnLabel(i).equalsIgnoreCase("payload")){byte[] bytes=r.getBytes(i);row.add(bytes==null?null:"0x"+HexFormat.of().formatHex(bytes));}
            else{String text=r.getString(i);boolean exact=type==Types.DECIMAL||type==Types.NUMERIC||name.contains("LARGEINT")||name.contains("UNSIGNED");row.add(text==null||!exact?text:new BigDecimal(text).stripTrailingZeros().toPlainString());}}result.add(row);}}
        result.sort(Comparator.comparing(Object::toString));return result;}
    private long count(String name)throws Exception{return Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)));}
    private String single(String query)throws Exception{try(var s=control.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff,boolean streamLoad)throws Exception{return execute(crossRequest(ProductTypeEnum.STARROCKS,schema,ProductTypeEnum.STARROCKS,schema,sourceTable,targetTable,diff),streamLoad);}
    private MigrationRequest crossRequest(ProductTypeEnum sourceType,String sourceSchema,ProductTypeEnum targetType,String targetSchema,String targetTable,boolean diff){return crossRequest(sourceType,sourceSchema,targetType,targetSchema,"source_rows",targetTable,diff);}
    private MigrationRequest crossRequest(ProductTypeEnum sourceType,String sourceSchema,ProductTypeEnum targetType,String targetSchema,String sourceTable,String targetTable,boolean diff){
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(sourceType);source.setUrl(url(sourceType));source.setUsername(user(sourceType));source.setPassword(password(sourceType));
        source.setSourceSchema(sourceSchema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);source.setRegexTableMapper(List.of(new PatternMapper("^"+sourceTable+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(targetType);target.setUrl(url(targetType));target.setUsername(user(targetType));target.setPassword(password(targetType));target.setTargetSchema(targetSchema);target.setPoolSize(1);target.setWriterEngineInsert(true);
        target.setChangeDataSync(diff);if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {
            var metadata=runtime.metadata(new DriverManagerDataSource(url(sourceType),user(sourceType),password(sourceType)),sourceType);
            var preview=runtime.products().typeMappings().plan(targetType,metadata.queryTableColumnMetaWithNativeEvidence(sourceSchema,sourceTable),
                    metadata.queryTablePrimaryKeys(sourceSchema,sourceTable),cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyColumnAcceptance.rejectAll());
            request.setTypeMappingAcknowledgements(preview.columns().stream()
                    .filter(c->c.fidelity()==cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.LOSSY)
                    .map(c->cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement.forColumn(targetSchema,targetTable,c)).toList());
        }
        return request;}
    private DbSwitchResult execute(MigrationRequest request,boolean streamLoad)throws Exception{try(var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),options(streamLoad),null)){return new MigrationService(request,ignored->{},null,runtime).run();}}
    private static DbSwitchOptions options(boolean streamLoad){return new DbSwitchOptions(128L*1024*1024,true,streamLoad,30,0,300000,30000,0);}
    private String table(String name){return schema+'.'+name;}
    private void sql(String text)throws Exception{sql(control,text);}
    private static void sql(Connection connection,String text)throws SQLException{try(var s=connection.createStatement()){s.setQueryTimeout(60);s.execute(text);}}
    private static Connection pg()throws SQLException{return DriverManager.getConnection(url(ProductTypeEnum.POSTGRESQL),user(ProductTypeEnum.POSTGRESQL),password(ProductTypeEnum.POSTGRESQL));}
    private static String url(){return System.getProperty("datahub.test.starrocks.url");}
    private static String url(ProductTypeEnum product){return product==ProductTypeEnum.STARROCKS?url():System.getProperty("datahub.test.postgres.url");}
    private static String user(ProductTypeEnum product){return product==ProductTypeEnum.STARROCKS?"root":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum product){return product==ProductTypeEnum.STARROCKS?"":System.getProperty("datahub.test.postgres.password","");}
    private static void success(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
}
