package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full public entry on the real StarRocks primary/duplicate models, not a MySQL substitute. */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.starrocks.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationStarrocksTest {
    private Connection control;
    private String schema;
    @BeforeEach void setup()throws Exception {
        control=DriverManager.getConnection(url(),"root","");
        try(var s=control.createStatement();var rows=s.executeQuery("SELECT current_version()")) {
            assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("3.5.21"));
        }
        schema="migration_"+UUID.randomUUID().toString().replace("-","");sql("CREATE DATABASE "+schema);
    }
    @AfterEach void cleanup()throws Exception {
        if(control!=null)try(var owned=control){if(schema!=null)sql("DROP DATABASE "+schema+" FORCE");}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void primaryModelFullAndDiffPreserveExactRowsWithBothWriteTransports(boolean streamLoad)throws Exception {
        create("source_rows","PRIMARY");create("target_rows","PRIMARY");seed("source_rows");
        var result=run("source_rows","target_rows",false,streamLoad);assertSuccess(result);
        assertEquals(503,result.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertRows("source_rows","target_rows");
        sql("DELETE FROM "+table("source_rows")+" WHERE id=9007199254740993");
        sql("UPDATE "+table("source_rows")+" SET amount=-0.000000000000000001,note='changed' WHERE id=9007199254740994");
        sql("INSERT INTO "+table("source_rows")+" (part,id,amount) VALUES(1,9223372036854775807,99999999999999999999.999999999999999999)");
        assertSuccess(run("source_rows","target_rows",true,streamLoad));assertRows("source_rows","target_rows");
        assertSuccess(run("source_rows","target_rows",true,streamLoad));assertRows("source_rows","target_rows");
    }
    @Test void autoCreatePreservesPrimaryModelDecimalAndMicroseconds()throws Exception {
        create("source_rows","PRIMARY");seed("source_rows");
        assertSuccess(run("source_rows","created_rows",false,true));assertRows("source_rows","created_rows");
        assertEquals(catalog("source_rows"),catalog("created_rows"));
        assertEquals("PRIMARY_KEYS",model("created_rows"));
    }
    @Test void duplicateModelFullPreservesDuplicateRows()throws Exception {
        create("source_rows","DUPLICATE");create("target_rows","DUPLICATE");
        sql("INSERT INTO "+table("source_rows")+"(part,id,note) VALUES(1,1,'a'),(1,1,'b'),(1,2,NULL)");
        assertSuccess(run("source_rows","target_rows",false,true));assertRows("source_rows","target_rows");
    }
    @Test void duplicateModelAutoCreateRetainsDuplicateModelAndRows()throws Exception {
        create("source_rows","DUPLICATE");sql("INSERT INTO "+table("source_rows")+"(part,id,note) VALUES(1,1,'a'),(1,1,'b')");
        assertSuccess(run("source_rows","created_rows",false,false));assertRows("source_rows","created_rows");
        assertEquals("DUP_KEYS",model("created_rows"));assertEquals(catalog("source_rows"),catalog("created_rows"));
    }
    @Test void nativeSqlTypeWitnessPreservesEmptyAndNonemptyBinaryAtPoolSizeOne()throws Exception {
        create("source_rows","PRIMARY");
        try(var pool=new com.zaxxer.hikari.HikariDataSource()){
            pool.setJdbcUrl(url());pool.setUsername("root");pool.setPassword("");pool.setMaximumPoolSize(1);pool.setMinimumIdle(0);pool.setConnectionTimeout(1000);
            var operator=new cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.StarrocksDatabaseOperator(pool);
            String query="SELECT id,payload,note,amount FROM "+table("source_rows");
            try(var result=operator.queryTableData(query,List.of("id","payload","note","amount"))){assertEquals(Types.VARBINARY,result.getResultset().getMetaData().getColumnType(2));assertFalse(result.getResultset().next());}
            sql("INSERT INTO "+table("source_rows")+"(part,id,payload,note,amount) VALUES(1,1,x'0002ff','AAL/',0.000000000000000001)");
            try(var result=operator.queryTableData(query)){var rows=result.getResultset();assertTrue(rows.next());assertArrayEquals(new byte[]{0,2,(byte)255},(byte[])rows.getObject(2));assertEquals("AAL/",rows.getObject(3));assertEquals(new BigDecimal("0.000000000000000001"),rows.getObject(4));assertFalse(rows.next());}
        }
    }
    private List<List<String>> catalog(String table)throws Exception {
        var values=new ArrayList<List<String>>();try(var p=control.prepareStatement("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")){
            p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){while(r.next())values.add(List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));}}
        return values;
    }
    @Test void nativePlanCannotReplaceItsSourceThroughAnotherDefaultCatalog()throws Exception {
        create("source_rows","PRIMARY");sql("INSERT INTO "+table("source_rows")+"(part,id) VALUES(1,1)");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"root","");
        var target=new org.springframework.jdbc.datasource.DriverManagerDataSource(url()+schema,"root","");
        var definition=cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables.describe(source,schema,"source_rows",20);
        var mapping=new LinkedHashMap<String,String>();definition.fields().forEach(f->mapping.put(f,f));
        assertThrows(IllegalStateException.class,()->cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables.plan(target,definition,schema,"source_rows",mapping,true,20));
        assertEquals(1,rows("source_rows").size());
    }
    private String model(String table)throws Exception {try(var p=control.prepareStatement("SELECT TABLE_MODEL FROM information_schema.tables_config WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
        p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    private void create(String name,String model)throws Exception {
        sql("CREATE TABLE "+table(name)+"(part SMALLINT NOT NULL,id BIGINT NOT NULL,amount DECIMAL(38,18),note VARCHAR(256),payload VARBINARY,event_time DATETIME) "
                +model+" KEY(part,id) DISTRIBUTED BY HASH(part,id) BUCKETS 1 PROPERTIES('replication_num'='1')");
    }
    private void seed(String name)throws Exception {
        String marks=String.join(",",Collections.nCopies(503,"(?,?,?,?,?,?)"));
        try(var statement=control.prepareStatement("INSERT INTO "+table(name)+" VALUES"+marks)) {
            statement.setQueryTimeout(30);
            for(int i=0;i<503;i++) {
                int base=i*6;
                statement.setInt(base+1,i%2);statement.setLong(base+2,9007199254740993L+i);
                statement.setBigDecimal(base+3,i%3==0?null:new BigDecimal("12345678901234567890.123456789012345678"));
                statement.setString(base+4,i%3==0?null:"中文'\\\n😀"+i);
                if(i%3==0)statement.setNull(base+5,Types.VARBINARY);else statement.setBytes(base+5,new byte[]{0,(byte)i,(byte)255});
                statement.setTimestamp(base+6,Timestamp.valueOf("2026-10-03 12:34:56.123456"));
            }
            statement.executeUpdate();
        }
    }
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff,boolean streamLoad)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.STARROCKS);source.setUrl(url());source.setUsername("root");source.setPassword("");
        source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+sourceTable+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.STARROCKS);target.setUrl(url());target.setUsername("root");target.setPassword("");
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        var options=new DbSwitchOptions(128L*1024*1024,true,streamLoad,30,0,300000,30000,0);
        try(var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),options,null)) {
            return new MigrationService(request,ignored->{},null,runtime).run();
        }
    }
    private void assertRows(String source,String target)throws Exception {
        var left=rows(source);var right=rows(target);assertEquals(left.size(),right.size());
        for(int i=0;i<left.size();i++)for(int column=0;column<left.get(i).size();column++) {
            Object a=left.get(i).get(column),b=right.get(i).get(column);
            assertTrue(Objects.equals(a,b),"row="+i+", column="+column);
        }
    }
    private List<List<Object>> rows(String name)throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var s=control.createStatement();var rows=s.executeQuery("SELECT part,id,amount,note,payload,event_time FROM "+table(name)+" ORDER BY part,id,note")) {
            while(rows.next())result.add(Arrays.asList(rows.getInt(1),rows.getLong(2),rows.getBigDecimal(3),rows.getString(4),
                    rows.getBytes(5)==null?null:HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6)));
        }
        return result;
    }
    private String table(String name){return schema+'.'+name;}
    private void sql(String text)throws Exception {try(var s=control.createStatement()){s.setQueryTimeout(30);s.execute(text);}}
    private static String url(){return System.getProperty("datahub.test.starrocks.url");}
    private static void assertSuccess(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
}
