package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQL Server 2022 native catalogs and the public full migration path, including submillisecond TIME. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class MigrationNativeSchemaSqlServerTest {
    private Connection control;
    private String schema;
    private final Set<String> tables=new LinkedHashSet<>();
    @BeforeEach void setup() throws Exception {
        control=DriverManager.getConnection(url(),"sa",password());
        schema="native_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control) {
            for(String name:tables)sql("DROP TABLE IF EXISTS "+table(name));
            sql("DROP SCHEMA "+q(schema));
        }
    }
    @Test void catalogAndCreatePreserveNativeParametersCollationAndPrimaryKeyOrder()throws Exception {
        create("source]; rows",columns());
        var source=read("source]; rows");
        assertEquals(List.of("id","part"),source.primaryKeys());
        assertEquals(List.of("smallint","numeric(19,0)","decimal(38,18)","char(7)","nchar(5)","varchar(19)","nvarchar(31)",
                "binary(5)","varbinary(17)","datetime2(0)","datetime2(7)","time(0)","time(7)"),
                source.columns().stream().map(c->c.getMetadataEvidence().nativeType().value()).toList());
        assertEquals("Latin1_General_100_BIN2",source.columns().get(3).getMetadataEvidence().collation().value());
        tables.add("target]; rows");
        String ddl=GenerateSqlUtils.getDDLCreateTableSQL(new DatabaseSqlserverImpl(),source,schema,"target]; rows");
        assertTrue(ddl.contains("[target]]; rows]"));
        sql(ddl);assertSchemaEquals(source,read("target]; rows"));
        sql("INSERT INTO "+table("target]; rows")+"(part,id) VALUES(1,9007199254740993)");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT amount,[national] FROM "+table("target]; rows"))) {
            assertTrue(r.next());assertEquals("0.000000000000000001",r.getBigDecimal(1).toPlainString());assertEquals("中文'\\",r.getNString(2));
        }
    }
    @Test void publicFullRetainsNativeTypesAndSevenDigitTimeFraction()throws Exception {
        create("source_rows",columns());tables.add("target_rows");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1,9007199254740993,12345678901234567890.123456789012345678,'abc',N'中文','a\\b',N'中文''\\',"
                +"0x00FF00AA01,0x00FF,'2026-10-03T12:34:56','2026-10-03T12:34:56.1234567','12:34:56','12:34:56.1234567')");
        sql("INSERT INTO "+table("source_rows")+"(part,id,amount,[national]) VALUES(-32768,9223372036854775807,NULL,NULL)");
        var result=run("source_rows","target_rows",false,false);
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertSchemaEquals(read("source_rows"),read("target_rows"));
        assertEquals(values("source_rows"),values("target_rows"));
    }
    @ParameterizedTest @ValueSource(strings={"id INT PRIMARY KEY, calculated AS (id+1)",
            "id INT PRIMARY KEY, changed ROWVERSION", "id INT PRIMARY KEY, document XML"})
    void unsupportedNativeFeaturesFailBeforeDroppingExistingTarget(String definition)throws Exception {
        create("source_rows","("+definition+")");create("target_rows","(sentinel INT NOT NULL)");
        sql("INSERT INTO "+table("target_rows")+" VALUES(73)");
        var result=run("source_rows","target_rows",true,true);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(73,scalar("SELECT sentinel FROM "+table("target_rows")));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unreconstructedCheckConstraintRejectsBeforeDropForDataAndStructureOnly(boolean onlyCreate)throws Exception {
        create("source_rows","(id SMALLINT NOT NULL PRIMARY KEY,amount DECIMAL(10,2) CHECK(amount>=0))");
        create("target_rows","(sentinel INT NOT NULL)");sql("INSERT INTO "+table("target_rows")+" VALUES(73)");
        var result=run("source_rows","target_rows",true,onlyCreate);
        assertFalse(result.getIfAllSuccess().get());assertEquals(73,scalar("SELECT sentinel FROM "+table("target_rows")));
    }
    @Test void selectPermissionAloneCannotProveCatalogAbsence()throws Exception {
        create("source_rows","(id INT PRIMARY KEY)");String user="native_reader_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE USER "+q(user)+" WITHOUT LOGIN");
        try {
            sql("GRANT SELECT ON "+table("source_rows")+" TO "+q(user));
            sql("EXECUTE AS USER = '"+user+"'");
            try {assertThrows(SQLFeatureNotSupportedException.class,()->read("source_rows"));}
            finally {sql("REVERT");}
            sql("GRANT VIEW DEFINITION ON "+table("source_rows")+" TO "+q(user));
            sql("EXECUTE AS USER = '"+user+"'");
            try {assertThrows(SQLFeatureNotSupportedException.class,()->read("source_rows"));}
            finally {sql("REVERT");}
        } finally {sql("DROP USER "+q(user));}
    }
    @Test void sqlServer2000DoesNotAcquireModernNativeEvidence()throws Exception {
        create("source_rows","(id INT PRIMARY KEY)");
        assertThrows(SQLFeatureNotSupportedException.class,()->NativeSchemaReader.read(control,ProductTypeEnum.SQLSERVER2000,schema,"source_rows",20));
    }
    @Test void timeCompositeKeysDifferenceAndReplayPreserveMidnightAndDateBoundaries()throws Exception {
        String definition="(day_value DATE NOT NULL,tm7 TIME(7) NOT NULL,payload NVARCHAR(30),PRIMARY KEY(day_value,tm7))";
        create("source_rows",definition);tables.add("target_rows");
        sql("INSERT INTO "+table("source_rows")+" VALUES('20261003','00:00:00.0000001',N'early'),('20261003','23:59:59.9999999',N'late'),('20261004','00:00:00',NULL)");
        assertTrue(run("source_rows","target_rows",false,false).getIfAllSuccess().get());
        sql("DELETE FROM "+table("source_rows")+" WHERE tm7='00:00:00.0000001'");
        sql("UPDATE "+table("source_rows")+" SET payload=N'changed' WHERE tm7='23:59:59.9999999'");
        sql("INSERT INTO "+table("source_rows")+" VALUES('20261004','00:00:00.0000001',N'next day')");
        assertTrue(run("source_rows","target_rows",false,false,true).getIfAllSuccess().get());
        assertEquals(timeRows("source_rows"),timeRows("target_rows"));
        assertTrue(run("source_rows","target_rows",false,false,true).getIfAllSuccess().get());
        assertEquals(timeRows("source_rows"),timeRows("target_rows"));
    }
    @Test void timeWriterPreservesNullAndSessionSettingsAndRejectsSub100nsBeforeWrite()throws Exception {
        create("target_rows","(id INT PRIMARY KEY,tm0 TIME(0),tm7 TIME(7))");
        sql("SET LANGUAGE British; SET DATEFIRST 3; SET DATEFORMAT dmy");
        String before=session();
        var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource(control,true);
        var writer=new cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl(ds);
        var fields=List.of("id","tm0","tm7");writer.prepareWrite(schema,"target_rows",fields);
        var time=java.time.LocalTime.of(23,59,59,999999900);
        assertEquals(2,writer.write(fields,Arrays.asList(new Object[]{1,java.time.LocalTime.MIDNIGHT,time},new Object[]{2,null,null})));
        assertEquals(before,session());assertTrue(control.getAutoCommit());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT tm0,tm7 FROM "+table("target_rows")+" ORDER BY id")) {
            assertTrue(r.next());assertEquals("00:00:00",r.getString(1));assertEquals("23:59:59.9999999",r.getString(2));
            assertTrue(r.next());assertNull(r.getObject(1));assertNull(r.getObject(2));
        }
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,Arrays.asList(new Object[]{3,null,time},
                new Object[]{4,null,java.time.LocalTime.of(0,0,0,1)})));
        assertEquals(2,scalar("SELECT COUNT(*) FROM "+table("target_rows")));assertEquals(before,session());
    }
    private String session()throws Exception {
        try(var s=control.createStatement();var r=s.executeQuery("SELECT @@LANGUAGE,@@DATEFIRST,@@OPTIONS")) {
            assertTrue(r.next());return r.getString(1)+":"+r.getInt(2)+":"+r.getInt(3);
        }
    }
    private List<List<String>> timeRows(String name)throws Exception {
        List<List<String>> result=new ArrayList<>();
        try(var s=control.createStatement();var r=s.executeQuery("SELECT day_value,tm7,payload FROM "+table(name)+" ORDER BY day_value,tm7")) {
            while(r.next())result.add(Arrays.asList(r.getString(1),r.getString(2),r.getString(3)));
        }return result;
    }
    private String columns() {
        return "(part SMALLINT NOT NULL,id NUMERIC(19,0) NOT NULL,amount DECIMAL(38,18) DEFAULT (0.000000000000000001),"
                +"fixed_text CHAR(7) COLLATE Latin1_General_100_BIN2,national_fixed NCHAR(5),text_value VARCHAR(19),"
                +"[national] NVARCHAR(31) DEFAULT (N'中文''\\'),fixed_binary BINARY(5),payload VARBINARY(17),"
                +"dt0 DATETIME2(0),dt7 DATETIME2(7),tm0 TIME(0),tm7 TIME(7),PRIMARY KEY(id,part))";
    }
    private void assertSchemaEquals(TableSchemaMetadata left,TableSchemaMetadata right) {
        assertEquals(left.primaryKeys(),right.primaryKeys());
        assertEquals(left.columns().size(),right.columns().size());
        for(int i=0;i<left.columns().size();i++) {
            var a=left.columns().get(i).getMetadataEvidence();var b=right.columns().get(i).getMetadataEvidence();
            assertEquals(a.nativeType(),b.nativeType());assertEquals(a.nullable(),b.nullable());assertEquals(a.collation(),b.collation());
            assertEquals(a.defaultExpression(),b.defaultExpression());
        }
    }
    private List<List<String>> values(String table)throws Exception {
        List<List<String>> result=new ArrayList<>();
        try(var s=control.createStatement();var r=s.executeQuery("SELECT * FROM "+table(table)+" ORDER BY id,part")) {
            while(r.next()) {List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));result.add(row);}
        }return result;
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult run(String from,String to,boolean drop,boolean only)throws Exception {
        return run(from,to,drop,only,false);
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult run(String from,String to,boolean drop,boolean only,boolean diff)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.SQLSERVER);source.setUrl(url());source.setUsername("sa");source.setPassword(password());
        source.setSourceSchema(schema);source.setSourceIncludes(from);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(from)+"$",to)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.SQLSERVER);target.setUrl(url());target.setUsername("sa");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setTargetDrop(drop);target.setOnlyCreate(only);target.setSyncDefaultVal(true);
        target.setChangeDataSync(diff);if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private TableSchemaMetadata read(String name)throws Exception{return NativeSchemaReader.read(control,ProductTypeEnum.SQLSERVER,schema,name,20);}
    private void create(String name,String columns)throws Exception{tables.add(name);sql("CREATE TABLE "+table(name)+columns);}
    private int scalar(String text)throws Exception{try(var s=control.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());return r.getInt(1);}}
    private void sql(String text)throws Exception{try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(text);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '['+name.replace("]","]]")+']';}
    private static String url(){return System.getProperty("datahub.test.sqlserver.url");}
    private static String password(){return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD");}
}
