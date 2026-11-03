package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.PGStatement;
import org.postgresql.jdbc.PgArray;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Native finite one-dimensional ARRAY COPY; supplied PG16/18 only, never product-wide certification. */
@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresArrayCopyPostgreSqlTest {
    private static final List<String> FIELDS=List.of("id","shorts","integers","longs","flags","texts","numbers");
    private static final String DEFINITION="id integer PRIMARY KEY,shorts smallint[],integers integer[],longs bigint[],flags boolean[],texts text[],numbers numeric[]";
    private HikariDataSource pool;
    private Connection observer;
    private String schema;
    private String fault="";
    private final AtomicInteger copies=new AtomicInteger(), borrowed=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("datahub.test.postgres.url");
        pool=new HikariDataSource();pool.setJdbcUrl(url+"?socketTimeout=5");pool.setUsername(System.getProperty("datahub.test.postgres.user","dataforge_test"));
        pool.setMaximumPoolSize(2);pool.setMinimumIdle(0);pool.setConnectionTimeout(3000);
        observer=DriverManager.getConnection(url,pool.getUsername(),"");
        schema="array73_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+schema);
        sql("CREATE TABLE "+schema+".rows("+DEFINITION+")");
    }
    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();if(pool!=null)pool.close();
        if(observer!=null)try(var owned=observer){assertTrue(schema.matches("array73_[a-f0-9]{32}"));sql("DROP SCHEMA "+schema+" CASCADE");}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void detachedSourceClosedArraysPreserveEverySupportedScalarNullAndEmptyShape(boolean binary) throws Exception {
        sql("CREATE TABLE "+schema+".source("+DEFINITION+")");
        sql("INSERT INTO "+schema+".source VALUES(1,NULL,NULL,NULL,NULL,NULL,NULL),(2,'{}','{}','{}','{}','{}','{}')");
        try(var insert=observer.prepareStatement("INSERT INTO "+schema+".source VALUES(3,?,?,?,?,?,?)")){
            Object[][] values={{Short.MIN_VALUE,0,null,Short.MAX_VALUE},{Integer.MIN_VALUE,null,Integer.MAX_VALUE},
                    {Long.MIN_VALUE,null,Long.MAX_VALUE},{true,null,false},
                    {null,"","NULL","null","中文😀","a\"b","a\\b","{x}","a,b"," spaced ","\n"},
                    {null,new BigDecimal("1234567890123456789012345678.12345678"),new BigDecimal("0.00000001"),new BigDecimal("-1.20"),new BigDecimal("0.00000")}};
            String[] types={"int2","int4","int8","bool","text","numeric"};var allocated=new ArrayList<java.sql.Array>();
            try{for(int i=0;i<types.length;i++){var a=observer.createArrayOf(types[i],values[i]);allocated.add(a);insert.setArray(i+1,a);}assertEquals(1,insert.executeUpdate());}
            finally{for(var a:allocated)a.free();}
        }
        List<Object[]> input=new ArrayList<>();
        try(var source=pool.getConnection();var query=source.prepareStatement("SELECT * FROM "+schema+".source ORDER BY id")){
            query.unwrap(PGStatement.class).setPrepareThreshold(binary?-1:0);
            try(var result=query.executeQuery()){while(result.next()){
                Object[] row=new Object[FIELDS.size()];for(int i=0;i<row.length;i++)row[i]=result.getObject(i+1);
                if(result.getInt(1)==3)assertEquals(binary,((PgArray)row[1]).isBinary());
                input.add(BatchValueConverter.convert(List.<Object[]>of(row),FIELDS.size()).getFirst());
            }}
        }
        var before=input.stream().map(Object[]::clone).toList();var writer=writer();
        var receipt=writer.writeWithReceipt(FIELDS,input);assertEquals(3,receipt.acknowledgedRows());assertEquals(1,copies.get());
        if(observer.getMetaData().getDatabaseMajorVersion()==18)assertNull(receipt.receipt());else assertNotNull(receipt.receipt());
        assertEquivalent("source","rows");
        try(var query=observer.createStatement();var rows=query.executeQuery("SELECT array_ndims(texts),array_lower(texts,1),cardinality(texts) FROM "+schema+".rows WHERE id=2")){
            assertTrue(rows.next());assertNull(rows.getObject(1));assertNull(rows.getObject(2));assertEquals(0,rows.getInt(3));
        }
        for(int i=0;i<input.size();i++){assertArrayEquals(before.get(i),input.get(i));for(int j=1;j<FIELDS.size();j++)assertSame(before.get(i)[j],input.get(i)[j]);}
        assertPoolUsable();
    }

    @Test void conservativeNumericBinaryLimitsAndExactShortIntegerElementsRoundTrip() throws Exception {
        var writer=writer();var numbers=array("numeric",Types.NUMERIC,new BigDecimal(BigInteger.ONE,-131067),
                new BigDecimal(BigInteger.ONE,16383),new BigDecimal("1E+10"),new BigDecimal("-0.0012000"),new BigDecimal("0.0000"));
        var row=new Object[]{1,array("int2",Types.SMALLINT,(int)Short.MIN_VALUE,(int)Short.MAX_VALUE),null,null,null,null,numbers};
        assertEquals(1,writer.write(FIELDS,List.<Object[]>of(row)));
        try(var query=observer.createStatement();var rows=query.executeQuery("SELECT numbers,shorts FROM "+schema+".rows")){
            assertTrue(rows.next());var actual=(BigDecimal[])rows.getArray(1).getArray();
            assertEquals(numbers.elements().size(),actual.length);
            for(int i=0;i<actual.length;i++){var expected=(BigDecimal)numbers.elements().get(i);assertEquals(0,expected.compareTo(actual[i]));assertEquals(Math.max(0,expected.scale()),actual[i].scale());}
            assertArrayEquals(new Object[]{Short.MIN_VALUE,Short.MAX_VALUE},(Object[])rows.getArray(2).getArray());
        }
        assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings={"raw-array","list","jdbc-array","wrong-base","smallint-overflow","nul","high-surrogate","low-surrogate","numeric-scale","numeric-integer","numeric-min-scale"})
    void lateInvalidArrayRejectsWholeBatchBeforeBorrowingWithoutChangingSentinelOrInputs(String kind) throws Exception {
        var writer=writer();sql("INSERT INTO "+schema+".rows(id,texts) VALUES(99,ARRAY['retain'])");
        int column=5;Object wrong=switch(kind){
            case "raw-array"->new String[]{"a"};case "list"->List.of("a");case "jdbc-array"->observer.createArrayOf("text",new Object[]{"a"});
            case "wrong-base"->array("varchar",Types.VARCHAR,"a");
            case "smallint-overflow"->array("int2",Types.SMALLINT,32768);
            case "nul"->array("text",Types.VARCHAR,"a\0b");case "high-surrogate"->array("text",Types.VARCHAR,"x\uD800");
            case "low-surrogate"->array("text",Types.VARCHAR,"\uDC00x");
            case "numeric-scale"->array("numeric",Types.NUMERIC,new BigDecimal(BigInteger.ONE,16384));
            case "numeric-integer"->array("numeric",Types.NUMERIC,new BigDecimal(BigInteger.ONE,-131068));
            default->array("numeric",Types.NUMERIC,new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE));
        };
        if(kind.equals("smallint-overflow"))column=1;else if(kind.startsWith("numeric"))column=6;
        Object[] good={1,null,null,null,null,array("text",Types.VARCHAR,"safe😀"),null},bad={2,null,null,null,null,null,null};bad[column]=wrong;
        int before=borrowed.get();try{
            var failure=assertThrows(BatchRejectedException.class,()->writer.write(FIELDS,List.of(good,bad)));
            assertNull(failure.getCause());assertEquals(before,borrowed.get());assertEquals(0,copies.get());assertSame(wrong,bad[column]);
            assertEquals(1,count());assertSentinel();assertEquals(1,writer.write(FIELDS,List.<Object[]>of(good)));assertPoolUsable();
        }finally{if(wrong instanceof java.sql.Array a)a.free();}
    }

    @ParameterizedTest @ValueSource(strings={"uuid[]","varchar(3)[]","character(3)[]","numeric(5,2)[]","timestamp[]","integer[][]","domain-array","array-domain"})
    void unsupportedPhysicalArrayDefinitionsRefuseBeforeCopy(String kind) throws Exception {
        String type=kind;
        if(kind.equals("domain-array")){sql("CREATE DOMAIN "+schema+".part AS integer");type=schema+".part[]";}
        if(kind.equals("array-domain")){sql("CREATE DOMAIN "+schema+".parts AS integer[]");type=schema+".parts";}
        sql("CREATE TABLE "+schema+".unsupported(value "+type+")");var writer=new PostgresCopyWriterImpl(facade());
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.prepareWrite(schema,"unsupported",List.of("value")));
        assertEquals(0,copies.get());assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings={"array-oid","element-oid","element-name","element-namespace","element-kind","typmod"})
    void nativeArrayAndElementIdentityMustRemainConsistent(String kind) throws Exception {
        fault=kind;var writer=new PostgresCopyWriterImpl(facade());
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.prepareWrite(schema,"rows",FIELDS));assertEquals(0,copies.get());assertPoolUsable();
    }
    @Test void changedArrayElementAfterPreparationRefusesBeforeProtocol() throws Exception {
        var writer=writer();fault="element-oid";
        assertThrows(IllegalStateException.class,()->writer.write(FIELDS,List.<Object[]>of(new Object[]{1,null,null,null,null,array("text",Types.VARCHAR,"a"),null})));
        assertEquals(0,copies.get());assertEquals(0,count());assertPoolUsable();
    }
    @Test void primaryKeyFailurePreservesUnknownWriteAndRollsBackEntireArrayBatch() throws Exception {
        var writer=writer();Object[] row={1,null,null,null,null,array("text",Types.VARCHAR,"safe"),null};
        assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,()->writer.write(FIELDS,List.of(row,row.clone())));
        assertEquals(0,count());assertPoolUsable();assertEquals(1,writer.write(FIELDS,List.<Object[]>of(row)));assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings={"create","drop","truncate","append","constraint-failure"})
    void publicFullCopyMigrationPreservesArraysWithAutoDdlOrExistingTargetAndReleasesBudget(String mode) throws Exception {
        sql("CREATE TABLE "+schema+".source("+DEFINITION+")");sql("INSERT INTO "+schema+".source VALUES(1,'{1,NULL,-2}','{1,NULL,-2}','{1,NULL,-2}','{true,NULL,false}',ARRAY['中文😀','a,b','NULL',NULL],'{0.00000001,-1.20,NULL}')");
        sql("INSERT INTO "+schema+".source VALUES(2,'{}','{}','{}','{}','{}','{}'),(3,NULL,NULL,NULL,NULL,NULL,NULL)");
        if(!mode.equals("create")){
            sql("CREATE TABLE "+schema+".copied("+DEFINITION.replace("texts text[]", "texts text[]"+(mode.equals("constraint-failure")?" NOT NULL":""))+")");
            sql("INSERT INTO "+schema+".copied(id,texts) VALUES(99,ARRAY['retain'])");
        }
        Path artifact=Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var spec=new VerifiedDriverTestHandle.Spec(artifact.toString(),VerifiedDriverTestHandle.sha256(artifact),"org.postgresql.Driver");var budget=new ConnectionBudget(8,2);
        try(var drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL,spec),Map.of(),budget);var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers)){
            var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
            source.setUrl(System.getProperty("datahub.test.postgres.url"));source.setUsername(pool.getUsername());source.setPassword("");source.setDriverClassName("org.postgresql.Driver");
            source.setSourceSchema(schema);source.setIncludeOrExclude(1);source.setSourceTableNames(List.of("source"));source.setPoolSize(1);source.setRegexTableMapper(List.of(new PatternMapper("^source$","copied")));request.getSource().add(source);
            var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.POSTGRESQL);target.setUrl(source.getUrl());target.setUsername(source.getUsername());target.setPassword("");target.setDriverClassName("org.postgresql.Driver");target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(false);target.setTargetDrop(mode.equals("drop"));target.setTargetTruncateData(mode.equals("truncate"));
            var service=new MigrationService(request,ignored->{},null,runtime);try{var result=service.run();if(mode.equals("constraint-failure"))assertFalse(result.getIfAllSuccess().get());else{assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertEquals(3,result.getTableResultList().getFirst().getSyncCount().get());}}
            finally{service.physicalExit().toCompletableFuture().get(20,TimeUnit.SECONDS);}
        }
        assertEquals(new ConnectionBudget.Snapshot(8,2,0,0,0,0,0,0),budget.snapshot());
        if(mode.equals("constraint-failure")){
            try(var query=observer.createStatement();var rows=query.executeQuery("SELECT id,texts[1] FROM "+schema+".copied")){
                assertTrue(rows.next());assertEquals(99,rows.getInt(1));assertEquals("retain",rows.getString(2));assertFalse(rows.next());
            }
        }else{
            if(mode.equals("append")){try(var query=observer.createStatement();var rows=query.executeQuery("SELECT texts[1] FROM "+schema+".copied WHERE id=99")){assertTrue(rows.next());assertEquals("retain",rows.getString(1));}sql("DELETE FROM "+schema+".copied WHERE id=99");}
            assertEquivalent("source","copied");
        }
    }

    @Test void arrayCellByteBudgetRejectsBeforeBorrowAndAllowsSubsequentBatch() throws Exception {
        var writer=writer();writer.configureBatchLimits(new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(10,512,256,7,1000));
        Object[] row={1,null,null,null,null,array("text",Types.VARCHAR,"x".repeat(300)),null};int before=borrowed.get();
        assertThrows(BatchRejectedException.class,()->writer.write(FIELDS,List.<Object[]>of(row)));
        assertEquals(before,borrowed.get());assertEquals(0,copies.get());assertEquals(0,count());
        assertEquals(1,writer.write(FIELDS,List.<Object[]>of(new Object[]{2,null,null,null,null,array("text",Types.VARCHAR,"safe😀"),null})));assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings={"cell","batch"})
    void utf8ArrayBuffersRejectBeforeBorrowWhenJavaEstimateFitsCellOrBatch(String limit) throws Exception {
        var writer=writer();boolean cell=limit.equals("cell");
        writer.configureBatchLimits(cell?new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(10,1024,256,7,1000)
                :new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(10,1000,400,7,1000));
        String text="中".repeat(100);var array=array("text",Types.VARCHAR,text);
        assertEquals(248,array.estimatedBytes());
        List<Object[]> input=new ArrayList<>();for(int i=0;i<(cell?1:3);i++)input.add(new Object[]{i+1,null,null,null,null,array,null});
        assertEquals(cell?328:984,input.stream().mapToLong(BatchValueConverter::estimatedRowBytes).sum());
        int before=borrowed.get();var failure=assertThrows(BatchRejectedException.class,()->writer.write(FIELDS,input));
        assertTrue(failure.getMessage().contains("binary buffer"));assertNull(failure.getCause());
        assertEquals(before,borrowed.get());assertEquals(0,copies.get());assertEquals(0,count());
        for(var row:input)assertSame(array,row[5]);assertSame(text,array.elements().getFirst());
        assertEquals(1,writer.write(FIELDS,List.<Object[]>of(new Object[]{9,null,null,null,null,array("text",Types.VARCHAR,"safe😀"),null})));
        assertPoolUsable();
    }

    @Test void expandedScientificNumericJavaEstimateAlreadyRejectsBeforeAnyConnection() throws Exception {
        var writer=writer();writer.configureBatchLimits(new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(10,1024,128,7,1000));
        var array=array("numeric",Types.NUMERIC,new BigDecimal("1E+10000"));assertTrue(array.estimatedBytes()>20000);
        int before=borrowed.get();var failure=assertThrows(BatchRejectedException.class,()->writer.write(FIELDS,List.<Object[]>of(new Object[]{1,null,null,null,null,null,array})));
        assertFalse(failure.getMessage().contains("binary buffer"));assertEquals(before,borrowed.get());assertEquals(0,copies.get());assertEquals(0,count());
        assertEquals(1,writer.write(FIELDS,List.<Object[]>of(new Object[]{9,null,null,null,null,null,array("numeric",Types.NUMERIC,new BigDecimal("1E+3"))})));
        assertPoolUsable();
    }

    private void assertSentinel() throws SQLException {
        try(var query=observer.createStatement();var rows=query.executeQuery("SELECT id,texts[1] FROM "+schema+".rows")){
            assertTrue(rows.next());assertEquals(99,rows.getInt(1));assertEquals("retain",rows.getString(2));assertFalse(rows.next());
        }
    }
    private static JdbcArrayValue array(String type,int jdbc,Object...values){return new JdbcArrayValue(type,jdbc,Arrays.asList(values));}
    private PostgresCopyWriterImpl writer(){var writer=new PostgresCopyWriterImpl(facade());writer.preflightRuntimeWrite();writer.prepareWrite(schema,"rows",FIELDS);return writer;}
    private DataSource facade(){return(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{if(m.getName().equals("getConnection")){borrowed.incrementAndGet();return wrap(pool.getConnection());}return invoke(pool,m,a);});}
    private Connection wrap(Connection actual)throws Exception{
        PGConnection pg=actual.unwrap(PGConnection.class);PGConnection measured=(PGConnection)Proxy.newProxyInstance(PGConnection.class.getClassLoader(),new Class<?>[]{PGConnection.class},(p,m,a)->{if(m.getName().equals("getCopyAPI"))copies.incrementAndGet();return invoke(pg,m,a);});
        return(Connection)Proxy.newProxyInstance(PGConnection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("unwrap")&&a[0]==PGConnection.class)return measured;if(m.getName().equals("isWrapperFor")&&a[0]==PGConnection.class)return true;
            Object result=invoke(actual,m,a);
            if(m.getName().equals("prepareStatement")&&((String)a[0]).contains("a.atttypmod")){
                var query=(PreparedStatement)result;return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(qp,qm,qa)->{
                    Object value=invoke(query,qm,qa);if(!qm.getName().equals("executeQuery"))return value;var rows=(ResultSet)value;
                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->{
                        if(ra!=null&&ra.length>0&&ra[0] instanceof Integer c&&"_text".equals(rows.getString(8))){
                            if(rm.getName().equals("getInt")&&c==10&&fault.equals("typmod"))return 7;
                            if(rm.getName().equals("getString")){if(c==14&&fault.equals("array-oid"))return "1007";
                                if(c==15&&fault.equals("element-oid"))return "23";if(c==16&&fault.equals("element-name"))return "int4";
                                if(c==17&&fault.equals("element-namespace"))return schema;if(c==18&&fault.equals("element-kind"))return "d";}
                        }
                        return invoke(rows,rm,ra);
                    });
                });
            }return result;
        });
    }
    private static Object invoke(Object target,Method method,Object[]args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    private void sql(String sql)throws SQLException{try(var statement=observer.createStatement()){statement.execute(sql);}}
    private long count()throws SQLException{try(var statement=observer.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM "+schema+".rows")){assertTrue(rows.next());return rows.getLong(1);}}
    private void assertEquivalent(String first,String second)throws SQLException{try(var query=observer.createStatement();var rows=query.executeQuery("SELECT count(*) FROM ((SELECT * FROM "+schema+'.'+first+" EXCEPT SELECT * FROM "+schema+'.'+second+") UNION ALL (SELECT * FROM "+schema+'.'+second+" EXCEPT SELECT * FROM "+schema+'.'+first+")) diff")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}}
    private void assertPoolUsable()throws SQLException{try(var c=pool.getConnection();var query=c.createStatement();var rows=query.executeQuery("SELECT 1")){assertTrue(c.getAutoCommit());assertTrue(rows.next());assertEquals(1,rows.getInt(1));}assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());}
}
