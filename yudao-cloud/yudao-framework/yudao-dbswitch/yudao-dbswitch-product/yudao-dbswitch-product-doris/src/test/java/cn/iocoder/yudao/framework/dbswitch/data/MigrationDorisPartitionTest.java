package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.doris.DorisDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public MigrationService/JDBC paths on real Doris 4.0.8 static RANGE/LIST partitions; Stream Load is in DorisPartitionStreamLoadDorisTest. */
@Timeout(240)
@EnabledIfSystemProperty(named="datahub.test.doris.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationDorisPartitionTest {
    static final String RANGE="(k DATE NOT NULL,id BIGINT NOT NULL,amount DECIMAL(38,18),big LARGEINT,at DATETIME(6),note VARCHAR(200) COMMENT 'n''q',body STRING,"
            +"INDEX idx_note (note) USING INVERTED PROPERTIES(\"parser\"=\"unicode\") COMMENT 'inverted',INDEX idx_body (body) USING NGRAM_BF PROPERTIES(\"gram_size\"=\"3\",\"bf_size\"=\"256\")) ENGINE=OLAP UNIQUE KEY(k,id) COMMENT 'partitioned'"
            +" PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('2026-01-01'),PARTITION p2 VALUES [('2026-01-01'),('2026-02-01')),PARTITION pmax VALUES LESS THAN MAXVALUE)"
            +" DISTRIBUTED BY HASH(id) BUCKETS AUTO PROPERTIES('replication_num'='1','bloom_filter_columns'='id,k','enable_unique_key_merge_on_write'='true')";
    static final Map<String,String> SHAPES=Map.of(
            "range",RANGE,
            "multi","(a INT NOT NULL,b DATETIME(3) NOT NULL,v VARCHAR(20)) ENGINE=OLAP DUPLICATE KEY(a,b) PARTITION BY RANGE(a,b) (PARTITION p1 VALUES LESS THAN ('10','2026-01-01 00:00:00.500'),PARTITION p2 VALUES LESS THAN (MAXVALUE,MAXVALUE)) DISTRIBUTED BY RANDOM BUCKETS 2 PROPERTIES('replication_num'='1')",
            "list","(city VARCHAR(20) NOT NULL,id INT NOT NULL,v VARCHAR(20)) ENGINE=OLAP UNIQUE KEY(city,id) PARTITION BY LIST(city) (PARTITION p_bj VALUES IN ('beijing','中文'),PARTITION p_empty VALUES IN (''),PARTITION p_sh VALUES IN ('shanghai')) DISTRIBUTED BY HASH(city) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')",
            "listMulti","(id INT NOT NULL,city VARCHAR(20) NOT NULL,d DATE NOT NULL,v INT) ENGINE=OLAP DUPLICATE KEY(id,city,d) PARTITION BY LIST(id,city) (PARTITION p1 VALUES IN (('1','beijing'),('1','shanghai')),PARTITION p2 VALUES IN (('2','beijing'))) DISTRIBUTED BY RANDOM BUCKETS AUTO PROPERTIES('replication_num'='1')",
            "aggregate","(k INT NOT NULL,s BIGINT SUM DEFAULT '0',mx INT MAX,mn INT MIN,r VARCHAR(10) REPLACE,rn VARCHAR(10) REPLACE_IF_NOT_NULL) ENGINE=OLAP AGGREGATE KEY(k) PARTITION BY RANGE(k) (PARTITION p_low VALUES LESS THAN ('100'),PARTITION p_high VALUES LESS THAN MAXVALUE) DISTRIBUTED BY HASH(k) BUCKETS 2 PROPERTIES('replication_num'='1')",
            "nullList","(k INT NULL,c VARCHAR(10) NULL,id INT NOT NULL,v VARCHAR(20)) ENGINE=OLAP DUPLICATE KEY(k,c,id) PARTITION BY LIST(k,c) (PARTITION p1 VALUES IN (('1','a')),PARTITION pn VALUES IN ((NULL,'a'),(NULL,NULL),('2',NULL)),PARTITION ps VALUES IN (('3','NULL'),('3',''))) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
    static final Map<String,String> SEEDS=Map.of(
            "range","('2025-12-31',1,12345678901234567890.123456789012345678,170141183460469231731687303715884105727,'2026-10-03 12:34:56.123456','中文''\\\\',''),('2026-01-01',2,-0.000000000000000001,-170141183460469231731687303715884105728,'0001-01-01 00:00:00.000001',NULL,'NULL'),('2026-01-31',3,NULL,NULL,NULL,'x',NULL),('2026-02-01',4,0,0,'9999-12-31 23:59:59.999999','max',''),('9999-12-31',5,1,1,'2026-01-01 00:00:00','last','z')",
            "multi","(9,'9999-01-01 00:00:00',NULL),(9,'9999-01-01 00:00:00',NULL),(10,'2026-01-01 00:00:00.499','below'),(10,'2026-01-01 00:00:00.500','at'),(2147483647,'2026-01-01 00:00:00','max')",
            "list","('beijing',1,'a'),('中文',2,NULL),('',3,''),('shanghai',4,'d')",
            "listMulti","(1,'beijing','2026-01-01',1),(1,'beijing','2026-01-01',1),(1,'shanghai','2026-01-02',NULL),(2,'beijing','2026-01-03',3)",
            "aggregate","(1,5,5,5,'a','a'),(1,7,9,1,'b',NULL),(99,1,1,1,'x',NULL),(100,2,2,2,'y','y')",
            "nullList","(1,'a',1,'x'),(NULL,'a',2,'y'),(NULL,NULL,3,NULL),(2,NULL,4,'z'),(3,'NULL',5,'text'),(3,'',6,'')");
    /** The range seed for PostgreSQL literal rules (standard_conforming_strings): the same seven values. */
    static final String PG_SEED="('2025-12-31',1,12345678901234567890.123456789012345678,170141183460469231731687303715884105727,'2026-10-03 12:34:56.123456','中文''\\',''),('2026-01-01',2,-0.000000000000000001,-170141183460469231731687303715884105728,'0001-01-01 00:00:00.000001',NULL,'NULL'),('2026-01-31',3,NULL,NULL,NULL,'x',NULL),('2026-02-01',4,0,0,'9999-12-31 23:59:59.999999','max',''),('9999-12-31',5,1,1,'2026-01-01 00:00:00','last','z')";
    static final String PG_TABLE="(k date NOT NULL,id bigint NOT NULL,amount numeric(38,18),big numeric(39,0),at timestamp(6),note varchar(200),body text,PRIMARY KEY(k,id))";
    static final String COLUMNS="k,id,amount,big,at,note,body";
    private Connection control;private String schema;
    @BeforeEach void setup()throws Exception {control=DriverManager.getConnection(url(),"root","");try(var s=control.createStatement();var r=s.executeQuery("SHOW FRONTENDS")){assertTrue(r.next());assertTrue(r.getString("Version").startsWith("doris-4.0.8"));}schema="partition_"+UUID.randomUUID().toString().replace("-","");sql("CREATE DATABASE "+schema);}
    @AfterEach void cleanup()throws Exception {if(control!=null)try(var owned=control){if(schema!=null)sql("DROP DATABASE "+schema+" FORCE");}}

    @ParameterizedTest @ValueSource(strings={"range","multi","list","listMulti","aggregate","nullList"})
    void nativePartitionedStructureAndRowsRoundTripThroughAutoCreatedFullMigration(String shape)throws Exception {
        create("source_rows",shape);sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get(shape));
        if(shape.equals("aggregate"))sql("INSERT INTO "+table("source_rows")+" VALUES (1,1,0,0,'c',NULL)");
        success(run("source_rows","created_rows",false));
        assertEquals(shape("source_rows"),shape("created_rows"));assertNotNull(shape("created_rows").partitioning());
        assertEquals(partitions("source_rows"),partitions("created_rows"));assertEquals(rows("source_rows"),rows("created_rows"));
        if(shape.equals("range")){assertEquals(Map.of("p1",1L,"p2",2L,"pmax",2L),partitions("created_rows"));assertEquals(List.of("2025-12-31","1","12345678901234567890.123456789012345678","170141183460469231731687303715884105727","2026-10-03 12:34:56.123456","中文'\\",""),rows("created_rows").getFirst());assertEquals(0,shape("created_rows").buckets());assertEquals("k, id",shape("created_rows").properties().get("bloom_filter_columns"));assertEquals(2,shape("created_rows").indexes().size());}
        if(shape.equals("multi"))assertEquals(Map.of("p1",3L,"p2",2L),partitions("created_rows"));
        if(shape.equals("list"))assertEquals(Map.of("p_bj",2L,"p_empty",1L,"p_sh",1L),partitions("created_rows"));
        if(shape.equals("aggregate"))assertEquals(List.of("1","13","9","0","c","a"),rows("created_rows").getFirst(),"Merged aggregate rows are copied once, not re-aggregated");
        if(shape.equals("nullList")){assertEquals(Map.of("p1",1L,"pn",3L,"ps",2L),partitions("created_rows"),"NULL keys, the text 'NULL' and '' keep their partitions");
            assertEquals(3L,Long.parseLong(single("SELECT COUNT(*) FROM "+table("created_rows")+" PARTITION (pn) WHERE k IS NULL OR c IS NULL")));}
    }
    @Test void mergeOnReadStructureIsRecreatedWhileDataTransferIsRejectedBeforeAnyDdl()throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(city VARCHAR(20) NOT NULL,id INT NOT NULL,v VARCHAR(20)) ENGINE=OLAP UNIQUE KEY(city,id) PARTITION BY LIST(city) (PARTITION p1 VALUES IN ('a'),PARTITION p2 VALUES IN ('b')) DISTRIBUTED BY HASH(city) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='false')");
        sql("INSERT INTO "+table("source_rows")+" VALUES ('a',1,'x')");
        var onlyCreate=request("source_rows","structure_only",false);onlyCreate.getTarget().setOnlyCreate(true);success(execute(onlyCreate));assertEquals(shape("source_rows"),shape("structure_only"));
        assertFalse(execute(request("source_rows","with_rows",false)).getIfAllSuccess().get());assertFalse(exists("with_rows"),"Rejected data protocol must not leave a newly created target");
    }
    /** Scheduler-managed, automatic, per-partition overrides and literals whose catalog rendering is ambiguous never become a heap or a different partition set. */
    @ParameterizedTest @ValueSource(strings={"dynamic","automatic","bucketOverride","forgedLiteral","comma","charFilter","hyphenName"})
    void schedulerManagedOrUnrenderableSourcesAreRejectedBeforeTargetDdl(String form)throws Exception {
        switch(form){
            case "dynamic"->sql("CREATE TABLE "+table("source_rows")+"(k DATE NOT NULL,id INT NOT NULL) ENGINE=OLAP DUPLICATE KEY(k,id) PARTITION BY RANGE(k) () DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','dynamic_partition.enable'='true','dynamic_partition.time_unit'='DAY','dynamic_partition.start'='-3','dynamic_partition.end'='3','dynamic_partition.prefix'='p','dynamic_partition.buckets'='1')");
            case "automatic"->{sql("CREATE TABLE "+table("source_rows")+"(k DATETIME NOT NULL,id INT NOT NULL) ENGINE=OLAP DUPLICATE KEY(k,id) AUTO PARTITION BY RANGE (date_trunc(k,'month')) () DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");sql("INSERT INTO "+table("source_rows")+" VALUES ('2026-01-05 00:00:00',1)");}
            case "bucketOverride"->{create("source_rows","list");sql("ALTER TABLE "+table("source_rows")+" ADD PARTITION p_new VALUES IN ('x') DISTRIBUTED BY HASH(city) BUCKETS 5");}
            // One stored value a","b renders exactly like the two values "a","b" in SHOW CREATE.
            case "forgedLiteral"->sql("CREATE TABLE "+table("source_rows")+"(c VARCHAR(40) NOT NULL,id INT NOT NULL) ENGINE=OLAP DUPLICATE KEY(c,id) PARTITION BY LIST(c) (PARTITION p1 VALUES IN ('a\",\"b')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            case "comma"->sql("CREATE TABLE "+table("source_rows")+"(c VARCHAR(40) NOT NULL,id INT NOT NULL) ENGINE=OLAP DUPLICATE KEY(c,id) PARTITION BY LIST(c) (PARTITION p1 VALUES IN ('c, d','e')) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
            case "charFilter"->sql("CREATE TABLE "+table("source_rows")+"(k INT NOT NULL,t VARCHAR(100),INDEX idx_t (t) USING INVERTED PROPERTIES(\"parser\"=\"english\",\"char_filter_type\"=\"char_replace\",\"char_filter_pattern\"=\"._\")) ENGINE=OLAP DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
            default->sql("CREATE TABLE "+table("source_rows")+"(k INT NOT NULL) ENGINE=OLAP DUPLICATE KEY(k) PARTITION BY RANGE(k) (PARTITION `p-1` VALUES LESS THAN ('10')) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        }
        var result=execute(request("source_rows","created_rows",false));assertFalse(result.getIfAllSuccess().get());assertFalse(exists("created_rows"));
    }
    @Test void outOfPartitionRowsAreRejectedBeforeTheFirstJdbcRequestAndServerStrictModeNeverDropsThemSilently()throws Exception {
        sql("CREATE TABLE "+table("target_rows")+RANGE.replace(",PARTITION pmax VALUES LESS THAN MAXVALUE",""));
        var writer=new DorisWriterImpl(new DriverManagerDataSource(url(),"root",""));writer.configure(options());var fields=List.of("k","id","note");writer.prepareWrite(schema,"target_rows",fields);
        var rows=List.of(new Object[]{LocalDate.of(2026,1,31),1L,"inside"},new Object[]{LocalDate.of(2026,2,1),2L,"outside"});
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,rows));assertEquals(0,count("target_rows"));
        assertEquals(1,writer.write(fields,List.<Object[]>of(rows.getFirst())));assertEquals(Map.of("p1",0L,"p2",1L),partitions("target_rows"));
        // Defense in depth for the same rows if a caller bypassed pre-send routing: one strict statement, no silent subset.
        sql("SET enable_insert_strict=true");var failure=assertThrows(SQLException.class,()->sql("INSERT INTO "+table("target_rows")+"(k,id,note) VALUES ('2026-01-15',3,'inside'),('2026-02-01',4,'outside')"));
        assertTrue(failure.getMessage().contains("no partition"),failure::getMessage);assertEquals(1,count("target_rows"));
    }
    @Test void partitionedUniqueDifferenceAppliesInsertUpdateDeleteAcrossPartitionsAndRepeatsAsNoChange()throws Exception {
        create("source_rows","range");create("target_rows","range");sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("range"));
        success(run("source_rows","target_rows",false));assertEquals(rows("source_rows"),rows("target_rows"));
        sql("DELETE FROM "+table("source_rows")+" WHERE k='2025-12-31' AND id=1");sql("UPDATE "+table("source_rows")+" SET amount=-1.5,note='changed' WHERE k='2026-01-31' AND id=3");
        sql("INSERT INTO "+table("source_rows")+" (k,id,big,at) VALUES ('2030-06-01',6,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
        success(run("source_rows","target_rows",true));assertEquals(rows("source_rows"),rows("target_rows"));assertEquals(Map.of("p1",0L,"p2",3L,"pmax",3L),partitions("target_rows"));
        success(run("source_rows","target_rows",true));assertEquals(rows("source_rows"),rows("target_rows"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void zeroMutationReceiptOnPartitionedTargetIsResolvedOnlyByBoundedSameConnectionReadback(boolean skipped)throws Exception {
        create("target_rows","range");sql("INSERT INTO "+table("target_rows")+"(k,id,note) VALUES ('2026-01-15',2,'old')");
        var sync=new DorisDatabaseSyncImpl(zeroReceipts(skipped));sync.configure(options());var fields=List.of("k","id","note");sync.prepare(schema,"target_rows",fields,List.of("k","id"));
        if(skipped){var failure=assertThrows(PartialBatchWriteException.class,()->sync.executeUpdate(Collections.singletonList(new Object[]{LocalDate.of(2026,1,15),2L,"changed"})));
            assertEquals(0,failure.acknowledgedRows());assertEquals(1,failure.unknownRows());assertFalse(failure.terminationConfirmed());assertEquals("old",single("SELECT note FROM "+table("target_rows")));return;}
        assertEquals(1,sync.executeUpdate(Collections.singletonList(new Object[]{LocalDate.of(2026,1,15),2L,"changed"})));assertEquals("changed",single("SELECT note FROM "+table("target_rows")));
        assertEquals(1,sync.executeDelete(Collections.singletonList(new Object[]{LocalDate.of(2026,1,15),2L,"changed"})));assertEquals(0,count("target_rows"));
    }
    @ParameterizedTest @ValueSource(strings={"aggregate","listMulti","mergeOnRead"})
    void aggregateDuplicateAndMergeOnReadTargetsRejectDifferenceWithoutWriting(String shape)throws Exception {
        String ddl=shape.equals("mergeOnRead")?SHAPES.get("list").replace("'enable_unique_key_merge_on_write'='true'","'enable_unique_key_merge_on_write'='false'"):SHAPES.get(shape);
        String seed=SEEDS.get(shape.equals("mergeOnRead")?"list":shape);
        sql("CREATE TABLE "+table("source_rows")+ddl);sql("CREATE TABLE "+table("target_rows")+ddl);sql("INSERT INTO "+table("source_rows")+" VALUES "+seed);
        var columns=shape("target_rows").columns().stream().map(DorisNativeSchema.Column::name).toList();var keys=shape("target_rows").keys();
        assertThrows(IllegalStateException.class,()->new DorisDatabaseSyncImpl(new DriverManagerDataSource(url(),"root","")).prepare(schema,"target_rows",columns,keys));
        assertFalse(run("source_rows","target_rows",true).getIfAllSuccess().get());assertEquals(0,count("target_rows"));
    }
    /** PostgreSQL rows enter an existing native partitioned UNIQUE target; DIFF moves a key between partitions as delete + insert. */
    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.+")
    void postgresqlSourceFullAndDifferenceRouteEveryRowIntoNativeDorisPartitions()throws Exception {
        String pgSchema="olap_"+UUID.randomUUID().toString().replace("-","");
        try(var pg=pg()){sql(pg,"CREATE SCHEMA "+pgSchema);try{
            sql(pg,"CREATE TABLE "+pgSchema+".source_rows"+PG_TABLE);sql(pg,"INSERT INTO "+pgSchema+".source_rows VALUES "+PG_SEED);create("target_rows","range");
            success(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.DORIS,schema,"target_rows",false)));
            assertEquals(normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".source_rows"),normalized(control,"SELECT "+COLUMNS+" FROM "+table("target_rows")));assertEquals(Map.of("p1",1L,"p2",2L,"pmax",2L),partitions("target_rows"));
            sql(pg,"DELETE FROM "+pgSchema+".source_rows WHERE k='2025-12-31' AND id=1");sql(pg,"UPDATE "+pgSchema+".source_rows SET amount=-1.5,note='changed' WHERE k='2026-01-31' AND id=3");
            sql(pg,"DELETE FROM "+pgSchema+".source_rows WHERE k='9999-12-31' AND id=5");sql(pg,"INSERT INTO "+pgSchema+".source_rows(k,id,big,at) VALUES ('2025-06-01',5,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
            for(int pass=0;pass<2;pass++){success(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.DORIS,schema,"target_rows",true)));
                assertEquals(normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".source_rows"),normalized(control,"SELECT "+COLUMNS+" FROM "+table("target_rows")));assertEquals(Map.of("p1",1L,"p2",3L,"pmax",1L),partitions("target_rows"));}
            // The same source against a target without MAXVALUE: the batch fails explicitly before its first request, nothing is dropped silently.
            sql("CREATE TABLE "+table("bounded_rows")+RANGE.replace(",PARTITION pmax VALUES LESS THAN MAXVALUE",""));
            assertFalse(execute(crossRequest(ProductTypeEnum.POSTGRESQL,pgSchema,ProductTypeEnum.DORIS,schema,"bounded_rows",false)).getIfAllSuccess().get());assertEquals(0,count("bounded_rows"));
        }finally{sql(pg,"DROP SCHEMA "+pgSchema+" CASCADE");}}
    }
    /** A partitioned native source is read as plain rows into PostgreSQL: FULL, then DIFF including a cross-partition key move. */
    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.+")
    void partitionedDorisSourceFullAndDifferenceIntoPostgreSql()throws Exception {
        create("source_rows","range");sql("INSERT INTO "+table("source_rows")+" VALUES "+SEEDS.get("range"));String pgSchema="olap_"+UUID.randomUUID().toString().replace("-","");
        try(var pg=pg()){sql(pg,"CREATE SCHEMA "+pgSchema);try{
            sql(pg,"CREATE TABLE "+pgSchema+".target_rows"+PG_TABLE);
            success(execute(crossRequest(ProductTypeEnum.DORIS,schema,ProductTypeEnum.POSTGRESQL,pgSchema,"target_rows",false)));
            assertEquals(normalized(control,"SELECT "+COLUMNS+" FROM "+table("source_rows")),normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".target_rows"));
            sql("DELETE FROM "+table("source_rows")+" WHERE k='2025-12-31' AND id=1");sql("UPDATE "+table("source_rows")+" SET amount=-1.5,note='changed' WHERE k='2026-01-31' AND id=3");
            sql("DELETE FROM "+table("source_rows")+" WHERE k='9999-12-31' AND id=5");sql("INSERT INTO "+table("source_rows")+"(k,id,big,at) VALUES ('2025-06-01',5,-1,'2030-06-01 00:00:00.000001'),('2026-01-15',7,7,NULL)");
            for(int pass=0;pass<2;pass++){success(execute(crossRequest(ProductTypeEnum.DORIS,schema,ProductTypeEnum.POSTGRESQL,pgSchema,"target_rows",true)));
                assertEquals(normalized(control,"SELECT "+COLUMNS+" FROM "+table("source_rows")),normalized(pg,"SELECT "+COLUMNS+" FROM "+pgSchema+".target_rows"));}
        }finally{sql(pg,"DROP SCHEMA "+pgSchema+" CASCADE");}}
    }

    private DataSource zeroReceipts(boolean skipped){return new org.springframework.jdbc.datasource.AbstractDataSource(){
        @Override public Connection getConnection()throws SQLException{Connection actual=DriverManager.getConnection(url(),"root","");return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{Object result=invoke(m,actual,a);
            if(result instanceof PreparedStatement statement&&m.getName().equals("prepareStatement")&&((String)a[0]).matches("(?s)^(UPDATE|DELETE).*"))return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,method,args)->{
                if(!method.getName().equals("executeUpdate"))return invoke(method,statement,args);if(!skipped)invoke(method,statement,args);return 0;});return result;});}
        @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}};}
    private static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    private void create(String name,String shape)throws Exception{sql("CREATE TABLE "+table(name)+SHAPES.get(shape));}
    private DorisNativeSchema.Table shape(String name)throws Exception{return DorisJdbcSupport.inspect(control,schema,name,30).table();}
    private boolean exists(String name)throws Exception{try(var p=control.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){p.setString(1,schema);p.setString(2,name);try(var r=p.executeQuery()){assertTrue(r.next());return r.getLong(1)>0;}}}
    private Map<String,Long> partitions(String name)throws Exception{var names=new ArrayList<String>();try(var s=control.createStatement();var r=s.executeQuery("SHOW PARTITIONS FROM "+table(name))){while(r.next())names.add(r.getString("PartitionName"));}
        var counts=new TreeMap<String,Long>();for(String partition:names)counts.put(partition,Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)+" PARTITION ("+partition+")")));return counts;}
    private List<List<String>> rows(String name)throws Exception{var result=new ArrayList<List<String>>();try(var s=control.createStatement();var r=s.executeQuery("SELECT * FROM "+table(name))){int width=r.getMetaData().getColumnCount();while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=width;i++)row.add(r.getString(i));result.add(row);}}
        result.sort(Comparator.comparing(Object::toString));return result;}
    /** Product-neutral values: exact decimals without scale padding, timestamps as LocalDateTime text, everything else as text. */
    static List<List<String>> normalized(Connection c,String query)throws SQLException{var result=new ArrayList<List<String>>();try(var s=c.createStatement();var r=s.executeQuery(query)){var m=r.getMetaData();int width=m.getColumnCount();while(r.next()){var row=new ArrayList<String>();
        for(int i=1;i<=width;i++){int type=m.getColumnType(i);boolean exact=type==Types.DECIMAL||type==Types.NUMERIC||"LARGEINT".equalsIgnoreCase(m.getColumnTypeName(i));
            if(type==Types.TIMESTAMP){Timestamp time=r.getTimestamp(i);row.add(time==null?null:time.toLocalDateTime().toString());}
            else{String text=r.getString(i);row.add(text==null||!exact?text:new BigDecimal(text).stripTrailingZeros().toPlainString());}}result.add(row);}}
        result.sort(Comparator.comparing(Object::toString));return result;}
    private long count(String name)throws Exception{return Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)));}
    private String single(String query)throws Exception{try(var s=control.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private MigrationRequest request(String sourceTable,String targetTable,boolean diff){var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.DORIS);source.setUrl(url());source.setUsername("root");source.setPassword("");source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);source.setRegexTableMapper(List.of(new PatternMapper("^"+sourceTable+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.DORIS);target.setUrl(url());target.setUsername("root");target.setPassword("");target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");return request;}
    private MigrationRequest crossRequest(ProductTypeEnum sourceType,String sourceSchema,ProductTypeEnum targetType,String targetSchema,String targetTable,boolean diff){
        String sourceTable="source_rows";var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(sourceType);source.setUrl(url(sourceType));source.setUsername(user(sourceType));source.setPassword(password(sourceType));
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
    private DbSwitchResult run(String source,String target,boolean diff)throws Exception{return execute(request(source,target,diff));}
    private DbSwitchResult execute(MigrationRequest request)throws Exception{try(var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),options(),null)){return new MigrationService(request,ignored->{},null,runtime).run();}}
    private static DbSwitchOptions options(){return new DbSwitchOptions(128L*1024*1024,true,false,30,0,300000,30000,0);}
    private String table(String name){return DorisNativeSchema.relation(schema,name);}
    private void sql(String sql)throws Exception{sql(control,sql);}
    private static void sql(Connection connection,String sql)throws SQLException{try(var s=connection.createStatement()){s.setQueryTimeout(60);s.execute(sql);}}
    private static Connection pg()throws SQLException{return DriverManager.getConnection(url(ProductTypeEnum.POSTGRESQL),user(ProductTypeEnum.POSTGRESQL),password(ProductTypeEnum.POSTGRESQL));}
    private static String url(){return System.getProperty("datahub.test.doris.url");}
    private static String url(ProductTypeEnum product){return product==ProductTypeEnum.DORIS?url():System.getProperty("datahub.test.postgres.url");}
    private static String user(ProductTypeEnum product){return product==ProductTypeEnum.DORIS?"root":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum product){return product==ProductTypeEnum.DORIS?"":System.getProperty("datahub.test.postgres.password","");}
    private static void success(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
}
