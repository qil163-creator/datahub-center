package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.uxdb.url",matches="jdbc:uxdb://127[.]0[.]0[.]1:[0-9]+/uxdb")
@EnabledIfEnvironmentVariable(named="DATAHUB_UXDB_TEST_PASSWORD",matches=".+")
class UxdbCopyNativeTest {
    private DataSource source;
    private final String schema="uxcopy88_"+UUID.randomUUID().toString().replace("-","");
    @BeforeEach void setup()throws Exception {
        java.util.logging.LogManager.getLogManager().reset();Class.forName(ProductTypeEnum.UXDB.getDriveClassName());
        source=new DriverManagerDataSource(System.getProperty("datahub.test.uxdb.url"),
                System.getProperty("datahub.test.uxdb.user","uxdb"),System.getenv("DATAHUB_UXDB_TEST_PASSWORD"));
        sql("CREATE SCHEMA "+schema);
    }
    @AfterEach void cleanup()throws Exception {if(source!=null)sql("DROP SCHEMA "+schema+" CASCADE");}
    @Test void publicCopyWithoutPrimaryKeyPreservesNativeScalarValues()throws Exception {
        sql("CREATE TABLE "+schema+".t(id bigint,a smallint,b integer,n numeric(38,12),f real,d double precision,"
                +"flag boolean,v varchar(30),ch char(5),note text,payload bytea,day_value date,tm time(6),ts timestamp(6))");
        var fields=List.of("id","a","b","n","f","d","flag","v","ch","note","payload","day_value","tm","ts");
        var writer=new UxdbCopyWriterImpl(source);writer.preflightRuntimeWrite();writer.prepareWrite(schema,"t",fields);
        String text="中文😀'\\\t\n\r\\N".repeat(8000);byte[] bytes={0,1,10,13,92,(byte)255};
        Object[] row={Long.MAX_VALUE,Short.MIN_VALUE,Integer.MIN_VALUE,new BigDecimal("99999999999999999999999999.999999999999"),
                1.5f,-2.25d,true,"中文'\\N","abc",text,bytes,LocalDate.of(2026,10,8),
                LocalTime.of(23,59,59,123456000),LocalDateTime.of(2026,10,8,1,2,3,654321000)};
        var result=writer.writeWithReceipt(fields,List.of(row,new Object[fields.size()]));
        assertEquals(2,result.acknowledgedRows());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,result.commitAcknowledgement());
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+schema+".t ORDER BY id DESC NULLS LAST")) {
            assertTrue(r.next());assertEquals(row[0],r.getLong(1));assertEquals(row[1],r.getShort(2));assertEquals(row[2],r.getInt(3));
            assertEquals(row[3],r.getBigDecimal(4));assertEquals(row[4],r.getFloat(5));assertEquals(row[5],r.getDouble(6));
            assertEquals(row[6],r.getBoolean(7));assertEquals(row[7],r.getString(8));assertEquals("abc  ",r.getString(9));
            assertEquals(text,r.getString(10));assertArrayEquals(bytes,r.getBytes(11));assertEquals(row[11],r.getObject(12,LocalDate.class));
            assertEquals("23:59:59.123456",r.getString(13));assertEquals(row[13],r.getObject(14,LocalDateTime.class));
            assertTrue(r.next());for(int i=1;i<=14;i++)assertNull(r.getObject(i));assertFalse(r.next());
        }
    }

    @Test void isolatedOriginalDriverUsesItsOwnNativeCopyApi()throws Exception {
        sql("CREATE TABLE "+schema+".t(id bigint)");
        try(var handle=new cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle(
                java.nio.file.Path.of(System.getProperty("datahub.test.uxdb.driver")),ProductTypeEnum.UXDB)) {
            var isolated=handle.dataSource(System.getProperty("datahub.test.uxdb.url"),ProductTypeEnum.UXDB.getDriveClassName(),
                    System.getProperty("datahub.test.uxdb.user","uxdb"),System.getenv("DATAHUB_UXDB_TEST_PASSWORD"),new Properties());
            var writer=new UxdbCopyWriterImpl(isolated);writer.preflightRuntimeWrite();writer.prepareWrite(schema,"t",List.of("id"));
            assertEquals(1,writer.write(List.of("id"),List.<Object[]>of(new Object[]{Long.MAX_VALUE})));assertEquals(1,count("t"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"create","replace","truncate"})
    void publicFullAndDiffReconcileRowsWithCopySelected(String mode)throws Exception {
        sql("CREATE TABLE "+schema+".s(id bigint PRIMARY KEY,n numeric(24,6),note varchar(60),ts timestamp(6),payload bytea,wide bigint,f real,d double precision)");
        sql("INSERT INTO "+schema+".s VALUES(9007199254740993,123456789012345678.123456,'中文',TIMESTAMP '2026-10-08 01:02:03.123456','\\x00ff',9223372036854775807,1.5,-2.25)");
        if(!mode.equals("create")) {
            sql("CREATE TABLE "+schema+".t(LIKE "+schema+".s INCLUDING ALL)");
            sql("INSERT INTO "+schema+".t(id,n,note) VALUES(7,7,'old target')");
        }
        var request=request(false);request.getTarget().setTargetDrop(mode.equals("replace"));request.getTarget().setTargetTruncateData(mode.equals("truncate"));
        success(request);assertEquals(rows("s"),rows("t"));
        sql("UPDATE "+schema+".s SET n=0.000001,note='changed'");
        sql("INSERT INTO "+schema+".s(id,n,note) VALUES(2,-0.000001,'inserted')");
        success(request(true));assertEquals(rows("s"),rows("t"));
        sql("DELETE FROM "+schema+".s WHERE id=2");success(request(true));assertEquals(rows("s"),rows("t"));
        success(request(true));assertEquals(rows("s"),rows("t"));
    }

    @ParameterizedTest @ValueSource(strings={"integer[]","timestamp with time zone","time with time zone","jsonb"})
    void unsupportedReplacementCannotDropSentinel(String type)throws Exception {
        sql("CREATE TABLE "+schema+".s(id bigint PRIMARY KEY,value "+type+")");sql("INSERT INTO "+schema+".s(id) VALUES(1)");
        sql("CREATE TABLE "+schema+".t(id bigint PRIMARY KEY,note text)");sql("INSERT INTO "+schema+".t VALUES(7,'preserve')");
        var request=request(false);request.getTarget().setTargetDrop(true);
        try(var runtime=DbSwitchRuntime.standalone()){assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());}
        assertEquals(1,count("t"));assertEquals("preserve",single("SELECT note FROM "+schema+".t"));
    }

    @ParameterizedTest @ValueSource(strings={"varchar","character varying","numeric(4,5)","decimal(4,5)",
            "numeric","decimal","numeric(0)","numeric(1001)","numeric(4,-1)","varchar(0)","char(0)",
            "varchar(10485761)","timestamp(7)","time(7)","integer(4)","bytea(4)","boolean(1)"})
    void unsupportedCustomTypeModifiersCannotDropSentinel(String type)throws Exception {
        sql("CREATE TABLE "+schema+".s(id bigint PRIMARY KEY,value bigint)");
        sql("INSERT INTO "+schema+".s VALUES(1,1)");
        sql("CREATE TABLE "+schema+".t(id bigint PRIMARY KEY,note text)");
        sql("INSERT INTO "+schema+".t VALUES(7,'preserve')");
        var request=request(false);request.getTarget().setTargetDrop(true);
        request.getTarget().setCustomDdlMap(Map.of("t","CREATE TABLE "+schema+".t(id bigint PRIMARY KEY,value "+type+")"));
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        }
        assertEquals(1,count("t"));assertEquals("preserve",single("SELECT note FROM "+schema+".t"));
    }

    @Test void customScalarTypeModifiersAgreeWithNativeCopyAdmission()throws Exception {
        var types=List.of("smallint","int2","integer","int","int4","bigint","int8",
                "numeric(1)","decimal(1,1)","numeric(1000,0)","numeric(1000,1000)","real","float4",
                "double precision","float8","boolean","bool","text","bytea","date",
                "varchar(1)","character varying(10485760)","char","character","char(1)","character(10485760)",
                "timestamp","timestamp(0)","timestamp(6)","time","time(0)","time(6)");
        var fields=new ArrayList<String>();fields.add("id");
        var columns=new StringBuilder("id bigint PRIMARY KEY");
        for(int i=0;i<types.size();i++){fields.add("v"+i);columns.append(",v").append(i).append(' ').append(types.get(i));}
        String ddl="CREATE TABLE "+schema+".t("+columns+")";
        assertDoesNotThrow(()->UxdbCopyTargetDefinition.validate(schema,"t",List.of(ddl),fields));
        sql("CREATE TABLE "+schema+".s("+columns+")");sql("INSERT INTO "+schema+".s(id) VALUES(1)");
        sql("CREATE TABLE "+schema+".t(id bigint PRIMARY KEY,note text)");sql("INSERT INTO "+schema+".t VALUES(7,'old')");
        var request=request(false);request.getTarget().setTargetDrop(true);request.getTarget().setCustomDdlMap(Map.of("t",ddl));
        success(request);
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+schema+".t")) {
            assertEquals(fields.size(),r.getMetaData().getColumnCount());assertTrue(r.next());assertEquals(1,r.getLong(1));
            for(int i=2;i<=fields.size();i++)assertNull(r.getObject(i));assertFalse(r.next());
        }
    }

    @ParameterizedTest @ValueSource(strings={"constraints","primary","unique","index"})
    void customStructuralLimitsCannotDropSentinel(String kind)throws Exception {
        int count=kind.equals("constraints")?257:33;
        var fields=java.util.stream.IntStream.range(0,count).mapToObj(i->"c"+i).toList();
        sql("CREATE TABLE "+schema+".s("+String.join(",",fields.stream().map(f->f+" smallint").toList())+")");
        sql("INSERT INTO "+schema+".s DEFAULT VALUES");
        sql("CREATE TABLE "+schema+".t(id bigint PRIMARY KEY,note text)");sql("INSERT INTO "+schema+".t VALUES(7,'preserve')");
        var request=request(false);request.getTarget().setTargetDrop(true);
        request.getTarget().setCustomDdlMap(Map.of("t",String.join(";",structuralPlan(kind,fields))));
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        }
        assertEquals(1,count("t"));assertEquals("preserve",single("SELECT note FROM "+schema+".t"));
    }

    @Test void pureStructuralPlanBoundariesMatchNativeLimits() {
        for(String kind:List.of("constraints","primary","unique","index")) {
            int limit=kind.equals("constraints")?256:32;
            var allowed=java.util.stream.IntStream.range(0,limit).mapToObj(i->"c"+i).toList();
            var rejected=java.util.stream.IntStream.range(0,limit+1).mapToObj(i->"c"+i).toList();
            assertDoesNotThrow(()->UxdbCopyTargetDefinition.validate(schema,"t",structuralPlan(kind,allowed),allowed));
            assertThrows(IllegalArgumentException.class,()->UxdbCopyTargetDefinition.validate(schema,"t",structuralPlan(kind,rejected),rejected));
        }
    }

    private List<String> structuralPlan(String kind,List<String> fields) {
        String columns=String.join(",",fields.stream().map(f->f+" smallint"
                +(kind.equals("constraints")?" CHECK("+f+" BETWEEN -128 AND 127)":"")).toList());
        String key=kind.equals("primary")?",PRIMARY KEY("+String.join(",",fields)+")"
                :kind.equals("unique")?",UNIQUE("+String.join(",",fields)+")":"";
        String create="CREATE TABLE "+schema+".t("+columns+key+")";
        return kind.equals("index")?List.of(create,"CREATE INDEX ix ON "+schema+".t("+String.join(",",fields)+")"):List.of(create);
    }

    @Test void wholeBatchPrecisionAndTextChecksRunBeforeCopy()throws Exception {
        sql("CREATE TABLE "+schema+".t(id integer,n numeric(5,2),v varchar(4),ts timestamp(3))");
        var fields=List.of("id","n","v","ts");var writer=new UxdbCopyWriterImpl(source);writer.prepareWrite(schema,"t",fields);
        Object[] valid={1,new BigDecimal("1.25"),"中文",LocalDateTime.of(2026,10,8,1,2,3,123000000)};
        for(Object[] invalid:List.of(new Object[]{2,new BigDecimal("1.001"),"ok",valid[3]},
                new Object[]{2,new BigDecimal("1000.00"),"ok",valid[3]},new Object[]{2,new BigDecimal("1"),"too long",valid[3]},
                new Object[]{2,new BigDecimal("1"),"x\0",valid[3]},new Object[]{2,new BigDecimal("1"),"ok",LocalDateTime.of(2026,10,8,1,2,3,123456000)})) {
            assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.of(valid,invalid)));assertEquals(0,count("t"));
        }
        assertEquals(1,writer.write(fields,List.<Object[]>of(valid)));assertEquals(1,count("t"));
    }

    @Test void truncateRefreshesOnlyTheOwnedGenerationAndWrongTargetIsUntouched()throws Exception {
        sql("CREATE TABLE "+schema+".t(id integer)");sql("INSERT INTO "+schema+".t VALUES(7)");
        var writer=new UxdbCopyWriterImpl(source);writer.prepareWrite(schema,"t",List.of("id"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.truncatePreparedTarget(schema,"s"));assertEquals(1,count("t"));
        writer.prepareWrite(schema,"t",List.of("id"));assertTrue(writer.truncatePreparedTarget(schema,"t"));assertEquals(0,count("t"));
        assertEquals(1,writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        sql("TRUNCATE TABLE "+schema+".t");sql("INSERT INTO "+schema+".t VALUES(9)");
        assertThrows(RuntimeException.class,()->writer.truncatePreparedTarget(schema,"t"));assertEquals(1,count("t"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{2})));
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void lostCommitResponseIsUnknownWithoutReplay(boolean truncate,boolean commitFirst)throws Exception {
        sql("CREATE TABLE "+schema+".t(id integer)");if(truncate)sql("INSERT INTO "+schema+".t VALUES(7)");
        var commits=new AtomicInteger();var fault=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {
                var real=source.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("commit")){commits.incrementAndGet();if(commitFirst)real.commit();throw new SQLException("synthetic lost commit response");}
                    try{return method.invoke(real,args);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
            @Override public Connection getConnection(String user,String password){throw new UnsupportedOperationException();}
        };
        var writer=new UxdbCopyWriterImpl(fault);writer.prepareWrite(schema,"t",List.of("id"));
        var failure=assertThrows(UnconfirmedWriteException.class,()->{if(truncate)writer.truncatePreparedTarget(schema,"t");
            else writer.write(List.of("id"),List.<Object[]>of(new Object[]{1}));});
        assertNull(failure.getCause());assertEquals(1,commits.get());assertEquals(truncate?(commitFirst?0:1):(commitFirst?1:0),count("t"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.write(List.of("id"),List.<Object[]>of(new Object[]{2})));
    }

    @Test void nativeConstraintFailureAbortsTheCopyWithoutPrefixOrInsertFallback()throws Exception {
        sql("CREATE TABLE "+schema+".t(id integer CHECK(id>0))");var writer=new UxdbCopyWriterImpl(source);writer.prepareWrite(schema,"t",List.of("id"));
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),List.of(new Object[]{1},new Object[]{-1})));
        assertEquals(0,count("t"));
    }

    private MigrationRequest request(boolean diff){
        var request=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.UXDB);
        s.setUrl(System.getProperty("datahub.test.uxdb.url"));s.setUsername(System.getProperty("datahub.test.uxdb.user","uxdb"));
        s.setPassword(System.getenv("DATAHUB_UXDB_TEST_PASSWORD"));s.setSourceSchema(schema);s.setSourceIncludes("s");s.setPoolSize(1);
        s.setRegexTableMapper(List.of(new PatternMapper("^s$","t")));request.getSource().add(s);
        var t=request.getTarget();t.setTargetProductType(ProductTypeEnum.UXDB);t.setUrl(s.getUrl());t.setUsername(s.getUsername());t.setPassword(s.getPassword());
        t.setTargetSchema(schema);t.setPoolSize(1);t.setWriterEngineInsert(false);t.setChangeDataSync(diff);if(diff)t.setChangeDataSyncType("PK_ORDER_COMPARE");return request;
    }
    private void success(MigrationRequest request)throws Exception{try(var runtime=DbSwitchRuntime.standalone()){
        var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}}
    private List<List<Object>> rows(String table)throws Exception{
        var result=new ArrayList<List<Object>>();try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT id,n,note,ts,payload,wide,f,d FROM "+schema+'.'+table+" ORDER BY id")){
            while(r.next())result.add(Arrays.asList(r.getLong(1),r.getBigDecimal(2),r.getString(3),r.getTimestamp(4),r.getBytes(5)==null?null:HexFormat.of().formatHex(r.getBytes(5)),r.getObject(6),r.getObject(7),r.getObject(8)));}return result;
    }
    private long count(String table)throws Exception{return Long.parseLong(single("SELECT COUNT(*) FROM "+schema+'.'+table));}
    private String single(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());var value=r.getString(1);assertFalse(r.next());return value;}}
    private void sql(String text)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.setQueryTimeout(10);s.execute(text);}}
}
