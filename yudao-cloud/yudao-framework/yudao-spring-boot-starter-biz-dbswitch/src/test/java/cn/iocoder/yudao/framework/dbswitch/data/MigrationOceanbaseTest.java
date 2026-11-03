package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public migration with the isolated OceanBase driver and the actual MySQL-mode product. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.oceanbase.url", matches="jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named="datahub.test.oceanbase.driver", matches=".+")
class MigrationOceanbaseTest {
    private Connection control;
    private ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private String schema;

    @BeforeEach void setup() throws Exception {
        drivers=drivers();
        runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        control=runtime.openConnection(ProductTypeEnum.OCEANBASE,null,url(),"root@test","");
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT version()")) {
            assertTrue(rows.next());assertTrue(rows.getString(1).contains("OceanBase"));
        }
        schema="migration_ob_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE "+q(schema));
    }
    @AfterEach void cleanup() throws Exception {
        try { if(control!=null)try(var ignored=control){if(schema!=null)sql("DROP DATABASE "+q(schema));} }
        finally {if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThenDiffRetainsExactValuesAndPhysicalNames(boolean quoted) throws Exception {
        String source=quoted?"source`; rows":"source_rows",target=quoted?"target`; rows":"target_rows";
        String note=quoted?"note`; value":"note";
        sql("CREATE TABLE "+table(source)+columns(note));
        sql("CREATE TABLE "+table(target)+columns(note));
        seed(source,note);
        assertSuccess(run(source,target,false));
        assertRowsEqual(source,target,note);
        sql("DELETE FROM "+table(source)+" WHERE `id`=9007199254740993");
        sql("UPDATE "+table(source)+" SET `amount`=-0.000000000000000001,"+q(note)+"='更新' WHERE `id`=9007199254740994");
        sql("INSERT INTO "+table(source)+" (`part`,`id`,`amount`) VALUES(1,9223372036854775807,99999999999999999999.999999999999999999)");
        assertSuccess(run(source,target,true));assertRowsEqual(source,target,note);
        assertSuccess(run(source,target,true));assertRowsEqual(source,target,note);
    }

    @ParameterizedTest @ValueSource(ints={0,3,6})
    void autoCreatePreservesNumericBinaryAndTimestampDomains(int scale) throws Exception {
        sql("CREATE TABLE "+table("source_rows")+columns("note").replace("DATETIME(6)","DATETIME("+scale+")"));
        seed("source_rows","note");
        var dialect=new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl();
        var metadata=dialect.queryTableColumnMeta(control,schema,"source_rows");
        var time=metadata.stream().filter(c->c.getFieldName().equals("event_time")).findFirst().orElseThrow();
        assertEquals(scale,time.getScaleSize(),"native metadata scale");
        assertEquals(scale,time.getMetaData().getLength(),"logical temporal scale");
        assertTrue(dialect.getFieldDefinition(time.getMetaData(),List.of(),false,false,false).contains("DATETIME("+scale+")"));
        assertSuccess(run("source_rows","target_rows",false));
        assertRowsEqual("source_rows","target_rows","note");
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT `amount`,`event_time` FROM "+table("target_rows")+" WHERE 1=0")) {
            assertEquals(38,rows.getMetaData().getPrecision(1));assertEquals(18,rows.getMetaData().getScale(1));
        }
        assertEquals(scale,dialect.queryTableColumnMeta(control,schema,"target_rows").stream()
                .filter(c->c.getFieldName().equals("event_time")).findFirst().orElseThrow().getScaleSize());
    }

    @Test void primaryKeyOnlyDiffHasNoEmptyUpdateClause() throws Exception {
        for(String name:List.of("source_rows","target_rows"))sql("CREATE TABLE "+table(name)+"(`id` BIGINT PRIMARY KEY)");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1),(3)");sql("INSERT INTO "+table("target_rows")+" VALUES(1),(2)");
        assertSuccess(run("source_rows","target_rows",true));assertSuccess(run("source_rows","target_rows",true));
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT `id` FROM "+table("target_rows")+" ORDER BY `id`")) {
            assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertTrue(rows.next());assertEquals(3,rows.getLong(1));assertFalse(rows.next());
        }
    }

    @ParameterizedTest @ValueSource(ints={0,3,6})
    void timeDurationRetainsSignLargeHoursAndFraction(int scale) throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(`id` INT PRIMARY KEY, `duration` TIME("+scale+"))");
        sql("INSERT INTO "+table("source_rows")+" VALUES (1,'-838:59:59.000000'),(2,'123:45:56.654321'),(3,'00:00:00.000001'),(4,NULL)");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT duration,CAST(duration AS CHAR) FROM "+table("source_rows")+" ORDER BY id")) {
            while(r.next()) {var bytes=r.getBytes(1);assertEquals(r.getString(2),bytes==null?null:new String(bytes,java.nio.charset.StandardCharsets.US_ASCII),"native TIME bytes");}
        }
        assertSuccess(run("source_rows","target_rows",false));assertEquals(durationRows("source_rows"),durationRows("target_rows"));
        sql("UPDATE "+table("source_rows")+" SET `duration`='-123:45:56.123456' WHERE id=2");
        sql("DELETE FROM "+table("source_rows")+" WHERE id=1");sql("INSERT INTO "+table("source_rows")+" VALUES(5,'838:59:59.000000')");
        assertSuccess(run("source_rows","target_rows",true));assertEquals(durationRows("source_rows"),durationRows("target_rows"));
        assertSuccess(run("source_rows","target_rows",true));assertEquals(durationRows("source_rows"),durationRows("target_rows"));
    }

    private List<String> durationRows(String table) throws Exception {
        var result=new ArrayList<String>();try(var s=control.createStatement();var r=s.executeQuery("SELECT id,CAST(duration AS CHAR) FROM "+table(table)+" ORDER BY id")) {
            while(r.next())result.add(r.getInt(1)+":"+r.getString(2));
        }return result;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"TIME,false", "TIME,true", "DATETIME,false", "DATETIME,true"})
    void lowerPrecisionTargetRejectsTheWholeBatchBeforeChangingRows(String type, boolean diff) throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(`id` INT PRIMARY KEY,`value` "+type+"(6))");
        sql("CREATE TABLE "+table("target_rows")+"(`id` INT PRIMARY KEY,`value` "+type+"(3))");
        String prefix=type.equals("TIME")?"-123:45:56":"2026-10-03 12:34:56";
        sql("INSERT INTO "+table("source_rows")+" VALUES(1,'"+prefix+".123000'),(2,'"+prefix+".123456')");
        if(diff)sql("INSERT INTO "+table("target_rows")+" VALUES(1,'"+prefix+".000'),(2,'"+prefix+".000')");
        var before=valueRows("target_rows");
        var result=run("source_rows","target_rows",diff);
        assertFalse(result.getIfAllSuccess().get(),"a lower-precision target must not silently round a value");
        assertEquals(before,valueRows("target_rows"),"validate every row before any batch write");
        sql("UPDATE "+table("source_rows")+" SET `value`='"+prefix+".123000' WHERE id=2");
        assertSuccess(run("source_rows","target_rows",diff));
        assertEquals(2,valueRows("target_rows").size());
        assertTrue(valueRows("target_rows").stream().allMatch(value->value.endsWith(".123")));
    }

    private List<String> valueRows(String name) throws Exception {
        var values=new ArrayList<String>();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT id,CAST(`value` AS CHAR) FROM "+table(name)+" ORDER BY id")) {
            while(rows.next())values.add(rows.getInt(1)+":"+rows.getString(2));
        }
        return values;
    }

    @Test void typedTemporalWritesRejectNanosecondLossAndPreserveInputRows() throws Exception {
        sql("CREATE TABLE "+table("typed_rows")+"(`id` INT PRIMARY KEY,`duration` TIME(6),`happened` DATETIME(6))");
        var fields=List.of("duration","id","happened");
        var writer=new cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseWriterImpl(dataSource());
        writer.prepareWrite(schema,"typed_rows",fields);
        var good=new Object[]{java.time.LocalTime.parse("12:34:56.123456"),1,java.time.LocalDateTime.parse("2026-10-03T12:34:56.123456")};
        for(int field:List.of(0,2)) {
            var bad=good.clone();bad[1]=2;
            bad[field]=field==0?java.time.LocalTime.parse("12:34:56.123456789"):Timestamp.valueOf("2026-10-03 12:34:56.123456789");
            assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,
                    ()->writer.write(fields,List.of(good,bad)));
            try(var s=control.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table("typed_rows"))) {
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }
        }
        assertEquals(1,writer.writeWithReceipt(fields,List.<Object[]>of(good)).acknowledgedRows());
        assertInstanceOf(java.time.LocalTime.class,good[0]);assertInstanceOf(java.time.LocalDateTime.class,good[2]);
        try(var s=control.createStatement();var rows=s.executeQuery("SELECT CAST(duration AS CHAR),CAST(happened AS CHAR) FROM "+table("typed_rows"))) {
            assertTrue(rows.next());assertEquals("12:34:56.123456",rows.getString(1));
            assertEquals("2026-10-03 12:34:56.123456",rows.getString(2));assertFalse(rows.next());
        }
        var sync=new cn.iocoder.yudao.framework.dbswitch.dbsynch.oceanbase.OceanbaseDatabaseSyncImpl(dataSource());
        sync.prepare(schema,"typed_rows",fields,List.of("id"));
        var badUpdate=new Object[]{java.time.LocalTime.parse("12:34:56.123456789"),1,good[2]};
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,
                ()->sync.executeUpdate(List.<Object[]>of(badUpdate)));
        // Only the matching key is sent to DELETE; non-key source values are not a lossy target write.
        assertEquals(1,sync.executeDelete(List.<Object[]>of(badUpdate)));
    }

    private javax.sql.DataSource dataSource() {
        return new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return runtime.openConnection(ProductTypeEnum.OCEANBASE,null,url(),"root@test",""); }
            @Override public Connection getConnection(String user,String password) throws SQLException { throw new SQLException("Explicit credentials are unavailable"); }
        };
    }

    @Test void temporalNullabilityAndFixedDefaultsSurviveCreate() throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(`id` INT PRIMARY KEY,`duration` TIME(6) NOT NULL DEFAULT '-123:45:56.123456',"
                +"`happened` DATETIME(6) NOT NULL DEFAULT '2026-10-03 12:34:56.123456',`day` DATE NOT NULL DEFAULT '2026-10-03')");
        assertSuccess(run("source_rows","target_rows",false,true));
        sql("INSERT INTO "+table("source_rows")+"(id) VALUES(1)");sql("INSERT INTO "+table("target_rows")+"(id) VALUES(1)");
        for(String column:List.of("duration","happened","day")) {
            try(var s=control.createStatement();var r=s.executeQuery("SELECT CAST(s."+q(column)+" AS CHAR),CAST(t."+q(column)+" AS CHAR) FROM "+table("source_rows")+" s JOIN "+table("target_rows")+" t ON s.id=t.id")) {
                assertTrue(r.next());assertEquals(r.getString(1),r.getString(2),column);
            }
            assertThrows(SQLException.class,()->sql("UPDATE "+table("target_rows")+" SET "+q(column)+"=NULL WHERE id=1"));
        }
    }

    private DbSwitchResult run(String sourceName,String targetName,boolean diff) throws Exception {
        return run(sourceName,targetName,diff,false);
    }
    private DbSwitchResult run(String sourceName,String targetName,boolean diff,boolean defaults) throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.OCEANBASE);source.setUrl(url());source.setUsername("root@test");source.setPassword("");
        source.setSourceSchema(schema);source.setSourceIncludes(sourceName);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceName)+"$",targetName)));
        request.getSource().add(source);var target=request.getTarget();
        target.setTargetProductType(ProductTypeEnum.OCEANBASE);target.setUrl(url());target.setUsername("root@test");target.setPassword("");
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        target.setSyncDefaultVal(defaults);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var drivers=drivers();var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers)) {
            return new MigrationService(request,ignored->{},null,runtime).run();
        }
    }
    private ConfiguredJdbcDrivers drivers() throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.oceanbase.driver")).toRealPath();
        var driver=new DbSwitchProperties.Driver();driver.setPath(jar.toString());
        driver.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        return new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.OCEANBASE,driver));
    }
    private void seed(String name,String note) throws Exception {
        try(var statement=control.prepareStatement("INSERT INTO "+table(name)+" (`part`,`id`,`amount`,"+q(note)+",`payload`,`event_time`) VALUES (?,?,?,?,?,?)")) {
            statement.setQueryTimeout(20);
            for(int i=0;i<503;i++) {
                statement.setInt(1,i%3);statement.setLong(2,9007199254740993L+i);
                statement.setBigDecimal(3,i%5==0?null:i%2==0?new BigDecimal("-0.000000000000000001"):new BigDecimal("12345678901234567890.123456789012345678"));
                statement.setString(4,i%7==0?null:"中文'\\\"; "+i);statement.setBytes(5,i%11==0?null:new byte[]{0,2,(byte)255});
                statement.setTimestamp(6,Timestamp.valueOf("2026-10-03 12:34:56.123456789"));statement.addBatch();
            }
            statement.executeBatch();
        }
    }
    private void assertRowsEqual(String source,String target,String note) throws Exception {
        var expected=rows(source,note);var actual=rows(target,note);
        assertEquals(expected.size(),actual.size());
        for(int i=0;i<expected.size();i++)assertEquals(expected.get(i),actual.get(i),"row "+i);
    }
    private List<List<Object>> rows(String name,String note) throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT `part`,`id`,`amount`,"+q(note)+",`payload`,`event_time` FROM "+table(name)+" ORDER BY `part`,`id`")) {
            while(rows.next())result.add(Arrays.asList(rows.getInt(1),rows.getLong(2),rows.getBigDecimal(3),rows.getString(4),
                    rows.getBytes(5)==null?null:HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6)));
        }
        return result;
    }
    private String columns(String note){return "(`part` INT NOT NULL,`id` BIGINT NOT NULL,`amount` DECIMAL(38,18),"+q(note)
            +" VARCHAR(192),`payload` VARBINARY(128),`event_time` DATETIME(6),PRIMARY KEY(`part`,`id`))";}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '`'+name.replace("`","``")+'`';}
    private static String url(){return System.getProperty("datahub.test.oceanbase.url");}
    private void sql(String sql)throws SQLException{try(var statement=control.createStatement()){statement.setQueryTimeout(20);statement.execute(sql);}}
    private static void assertSuccess(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
}
