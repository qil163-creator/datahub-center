package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServerSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorTransfer;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL Server 2022 type boundaries through the public FULL/DIFF/cursor entry points: money/smallmoney, tinyint,
 * uniqueidentifier, MAX LOBs, 100ns temporals with offsets and the 1582 calendar gap, computed/rowversion targets,
 * xml/CLR serializations and sql_variant. Every value is either reproduced exactly or rejected before target writes.
 */
@Timeout(240)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
@EnabledIfEnvironmentVariable(named="DATAHUB_TEST_SQLSERVER_PASSWORD",matches=".+")
class MigrationSqlServerTypesTest {
    private static String database,url;
    private static Connection admin;
    private Connection control,pg;
    private String schema,generationSchema;
    private SourceGenerationPin pin;
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private static final String TYPED="(id int NOT NULL PRIMARY KEY,m money NULL,sm smallmoney NULL,ti tinyint NULL,g uniqueidentifier NULL,d date NULL,"
            +"t7 time(7) NULL,d2 datetime2(7) NULL,d3 datetime2(3) NULL,o0 datetimeoffset(0) NULL,o7 datetimeoffset(7) NULL,dt datetime NULL,"
            +"sdt smalldatetime NULL,nv nvarchar(max) NULL,vc varchar(max) COLLATE Latin1_General_100_CI_AS NULL,vb varbinary(max) NULL,n38 numeric(38,10) NULL)";
    private static final String CANON="SELECT id,CONVERT(varchar(40),m,2),CONVERT(varchar(40),sm,2),CAST(ti AS varchar(5)),CAST(g AS char(36)),CONVERT(char(10),d,23),"
            +"CAST(t7 AS varchar(20)),CAST(d2 AS varchar(30)),CAST(d3 AS varchar(30)),CAST(o0 AS varchar(40)),CAST(o7 AS varchar(40)),CONVERT(varchar(30),dt,121),"
            +"CONVERT(varchar(30),sdt,121),CONCAT(DATALENGTH(nv),':',CONVERT(varchar(64),HASHBYTES('SHA2_256',nv),2)),"
            +"CONCAT(DATALENGTH(vc),':',CONVERT(varchar(64),HASHBYTES('SHA2_256',vc),2)),CONCAT(DATALENGTH(vb),':',CONVERT(varchar(64),HASHBYTES('SHA2_256',vb),2)),"
            +"CAST(n38 AS varchar(50)) FROM %s ORDER BY id";

    @BeforeAll static void database()throws Exception {
        String endpoint=System.getProperty("datahub.test.sqlserver.url");admin=DriverManager.getConnection(endpoint,"sa",password());
        database="codex_types_"+UUID.randomUUID().toString().replace("-","");
        sql(admin,"CREATE DATABASE "+q(database));sql(admin,"ALTER DATABASE "+q(database)+" SET ALLOW_SNAPSHOT_ISOLATION ON");
        url=endpoint.replace("databaseName=codex_q11","databaseName="+database);
    }
    @AfterAll static void databaseCleanup()throws Exception {
        if(admin!=null)try(var c=admin){if(database!=null){sql(c,"ALTER DATABASE "+q(database)+" SET SINGLE_USER WITH ROLLBACK IMMEDIATE");sql(c,"DROP DATABASE "+q(database));}}
    }
    @BeforeEach void setup()throws Exception {
        control=DriverManager.getConnection(url,"sa",password());assertEquals(16,control.getMetaData().getDatabaseMajorVersion());
        schema="t_"+UUID.randomUUID().toString().replace("-","");generationSchema="g_"+UUID.randomUUID().toString().replace("-","");
        sql(control,"CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {
        try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}}
        finally{if(control!=null)try(var c=control){for(String selected:List.of(schema,generationSchema)){
            List<String> tables=new ArrayList<>();try(var p=c.prepareStatement("SELECT t.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=?")){p.setString(1,selected);try(var r=p.executeQuery()){while(r.next())tables.add(r.getString(1));}}
            for(String table:tables)sql(c,"DROP TABLE "+q(selected)+'.'+q(table));sql(c,"IF SCHEMA_ID(N'"+selected+"') IS NOT NULL EXEC(N'DROP SCHEMA "+q(selected).replace("'","''")+"')");}}}
    }

    @Test void fullAndDiffReproduceMoneyGuidLobsAndHundredNanosecondTemporalsIncludingTheCalendarGap()throws Exception {
        create("src",TYPED);create("dst",TYPED);seedTyped("src");
        var full=run(table("src","dst"),false);assertSuccess(full);assertEquals(canon("src"),canon("dst"));
        sql(control,"UPDATE "+t("src")+" SET m=m-0.0001,o7='9999-12-31 23:59:59.9999998 +14:00',d2='1582-10-14 00:00:00.0000001' WHERE id=1");
        sql(control,"UPDATE "+t("src")+" SET g='A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A12',o0='2026-10-04 12:34:56 -07:00',ti=254 WHERE id=2");
        sql(control,"DELETE FROM "+t("src")+" WHERE id=3");
        sql(control,"INSERT INTO "+t("src")+"(id,m,ti,d,o7,dt,sdt) VALUES(5,1.2345,200,'1582-10-05','2000-01-01 00:00:00.0000005 +01:00','2026-10-04 12:00:00.007','2026-10-04 12:35:00')");
        // datetimeoffset has no exact difference encoding: the whole DIFF is rejected while spooling, before any target write.
        var before=canon("dst");assertFailedBeforeWrites(run(table("src","dst"),true));assertEquals(before,canon("dst"));
        var request=table("src","dst");request.getSource().getFirst().setRegexColumnMapper(List.of(new PatternMapper("^o[07]$","")));
        var diff=run(request,true);assertSuccess(diff);assertEquals(4,diff.getTableResultList().getFirst().getSyncCount().get());
        sql(control,"UPDATE d SET d.o0=s.o0,d.o7=s.o7 FROM "+t("dst")+" d JOIN "+t("src")+" s ON s.id=d.id");assertEquals(canon("src"),canon("dst"));
        var again=run(request,true);assertSuccess(again);assertEquals(0,again.getTableResultList().getFirst().getSyncCount().get());
    }

    @Test void autoCreateRebuildsVerifiedNativeTypesAndValues()throws Exception {
        String columns="(id int NOT NULL PRIMARY KEY,m money NULL,sm smallmoney NULL,ti tinyint NULL,d date NULL,t7 time(7) NULL,d2 datetime2(7) NULL,"
                +"dt datetime NULL,sdt smalldatetime NULL,nv nvarchar(max) NULL,vb varbinary(max) NULL,n38 numeric(38,10) NULL)";
        create("src",columns);
        sql(control,"INSERT INTO "+t("src")+" VALUES(1,922337203685477.5807,-214748.3648,255,'1582-10-10','23:59:59.9999999','1582-10-05 12:34:56.1234567',"
                +"'1753-01-01 00:00:00.003','2079-06-06 23:59:00',REPLICATE(CAST(N'汉😀' AS nvarchar(max)),40000),CAST(REPLICATE(CAST(0xAB AS varbinary(max)),70000) AS varbinary(max)),-0.0000000001),(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        assertSuccess(run(table("src","made"),false));
        var source=NativeSchemaReader.read(control,ProductTypeEnum.SQLSERVER,schema,"src",20).columns();
        var made=NativeSchemaReader.read(control,ProductTypeEnum.SQLSERVER,schema,"made",20).columns();
        for(int i=0;i<source.size();i++)assertEquals(source.get(i).getMetadataEvidence().nativeType().value(),made.get(i).getMetadataEvidence().nativeType().value());
        String canon="SELECT id,CONVERT(varchar(40),m,2),CONVERT(varchar(40),sm,2),CAST(ti AS varchar(5)),CONVERT(char(10),d,23),CAST(t7 AS varchar(20)),CAST(d2 AS varchar(30)),"
                +"CONVERT(varchar(30),dt,121),CONVERT(varchar(30),sdt,121),CONVERT(varchar(64),HASHBYTES('SHA2_256',nv),2),CONVERT(varchar(64),HASHBYTES('SHA2_256',vb),2),CAST(n38 AS varchar(50)) FROM %s ORDER BY id";
        assertEquals(rows(canon,"src"),rows(canon,"made"));
    }

    @Test void nativeEvidenceKeepsComputedExpressionsPersistenceAndExternalDependencies()throws Exception {
        sql(control,"CREATE FUNCTION "+t("twice")+"(@v int) RETURNS int WITH SCHEMABINDING AS BEGIN RETURN @v*2 END");
        try {
            create("calc","(id int NOT NULL PRIMARY KEY,base int NULL,name nvarchar(20) NULL,c AS (base*2),p AS (CONVERT(money,base)/3) PERSISTED NOT NULL,"
                    +"u AS (upper(name)),f AS ("+t("twice")+"(base)))");
            var columns=NativeSchemaReader.read(control,ProductTypeEnum.SQLSERVER,schema,"calc",20).columns();
            var evidence=columns.stream().collect(java.util.stream.Collectors.toMap(c->c.getFieldName(),c->c.getMetadataEvidence().generatedExpression()));
            assertEquals("([base]*(2))",evidence.get("c").value());assertEquals("(CONVERT([money],[base])/(3)) PERSISTED",evidence.get("p").value());
            assertEquals("(upper([name]))",evidence.get("u").value());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.UNSUPPORTED,evidence.get("f").state());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT,evidence.get("base").state());
            assertFalse(columns.stream().filter(c->c.getFieldName().equals("p")).findFirst().orElseThrow().isNullable());
            create("copy","(id int NOT NULL PRIMARY KEY,base int NULL,name nvarchar(20) NULL,c int NULL,p money NULL,u nvarchar(20) NULL,f int NULL)");
            sql(control,"INSERT INTO "+t("calc")+"(id,base,name) VALUES(1,10,N'abc')");
            assertSuccess(run(table("calc","copy"),false));
            assertEquals(List.of(List.of("1","10","abc","20","3.3333","ABC","20")),rows("SELECT CAST(id AS varchar(9)),CAST(base AS varchar(9)),name,CAST(c AS varchar(9)),"
                    +"CONVERT(varchar(40),p,2),u,CAST(f AS varchar(9)) FROM %s","copy"));
        } finally { sql(control,"DROP TABLE IF EXISTS "+t("calc"));sql(control,"DROP FUNCTION IF EXISTS "+t("twice")); }
    }

    @Test void serverGeneratedAndVariantTargetsAreRejectedBeforeTruncationAndWrites()throws Exception {
        create("src","(id int NOT NULL PRIMARY KEY,base int NULL,c int NULL)");sql(control,"INSERT INTO "+t("src")+" VALUES(1,20,99),(2,21,98)");
        Map<String,String> targets=new LinkedHashMap<>();
        targets.put("computed","(id int NOT NULL PRIMARY KEY,base int NULL,c AS (base*2))");
        targets.put("persisted","(id int NOT NULL PRIMARY KEY,base int NULL,c AS (base+1) PERSISTED)");
        targets.put("rowversion","(id int NOT NULL PRIMARY KEY,base int NULL,c rowversion)");
        targets.put("variant","(id int NOT NULL PRIMARY KEY,base int NULL,c sql_variant NULL)");
        for(var target:targets.entrySet()) {
            create(target.getKey(),target.getValue());sql(control,"INSERT INTO "+t(target.getKey())+"(id,base) VALUES(7,70)");
            var request=table("src",target.getKey());request.getTarget().setTargetTruncateData(true);
            var rejected=run(request,false);assertFailedBeforeWrites(rejected);
            assertEquals(List.of(List.of("7","70")),rows("SELECT CAST(id AS varchar(9)),CAST(base AS varchar(9)) FROM %s ORDER BY id",target.getKey()),target.getKey());
            var diff=run(table("src",target.getKey()),true);assertFailedBeforeWrites(diff);
            assertEquals(1,count(target.getKey()),target.getKey());
            if(target.getKey().equals("variant"))continue;
            var mapped=table("src",target.getKey());mapped.getTarget().setTargetTruncateData(true);mapped.getSource().getFirst().setRegexColumnMapper(List.of(new PatternMapper("^c$","")));
            assertSuccess(run(mapped,false));assertEquals(2,count(target.getKey()));
        }
        assertEquals(List.of(List.of("1","40"),List.of("2","42")),rows("SELECT CAST(id AS varchar(9)),CAST(c AS varchar(9)) FROM %s ORDER BY id","computed"));
        assertEquals(List.of(List.of("1","21"),List.of("2","22")),rows("SELECT CAST(id AS varchar(9)),CAST(c AS varchar(9)) FROM %s ORDER BY id","persisted"));
        var writer=new SqlServerUpsertWriter(ds());assertThrows(RuntimeException.class,()->writer.prepareUpsert(schema,"persisted",List.of("id","base"),List.of("id")));
        var direct=new SqlServerWriterImpl(ds());assertThrows(IllegalArgumentException.class,()->direct.prepareWrite(schema,"rowversion",List.of("id","base","c")));
    }

    @Test void sqlVariantSourceIsRejectedAndExplicitConversionIsCopiedExactly()throws Exception {
        create("src","(id int NOT NULL PRIMARY KEY,v sql_variant NULL)");create("dst","(id int NOT NULL PRIMARY KEY,v nvarchar(100) NULL)");
        sql(control,"INSERT INTO "+t("src")+" VALUES(1,CAST(12.5 AS money))");sql(control,"INSERT INTO "+t("src")+" VALUES(2,CAST(N'汉' AS nvarchar(10)))");
        sql(control,"INSERT INTO "+t("src")+" VALUES(3,CAST('2026-10-04T01:02:03.1234567' AS datetime2(7)))");
        sql(control,"INSERT INTO "+t("dst")+" VALUES(7,N'kept')");
        var replace=table("src","dst");replace.getTarget().setTargetTruncateData(true);
        assertFailedBeforeWrites(run(replace,false));assertEquals(List.of(List.of("7","kept")),rows("SELECT CAST(id AS varchar(9)),v FROM %s","dst"));
        assertFailedBeforeWrites(run(table("src","dst"),true));assertEquals(1,count("dst"));sql(control,"DELETE FROM "+t("dst"));
        var request=table("src","dst");var source=request.getSource().getFirst();source.setSourceType(2);source.setSourcePrimaryKeys(List.of("id"));
        source.setSourceSql("SELECT id,CAST(v AS nvarchar(100)) AS v FROM "+t("src"));request.getTarget().setTargetTable("dst");
        assertSuccess(run(request,false));
        assertEquals(rows("SELECT CAST(id AS varchar(9)),CAST(v AS nvarchar(100)) FROM %s ORDER BY id","src"),rows("SELECT CAST(id AS varchar(9)),v FROM %s ORDER BY id","dst"));
    }

    @Test void xmlAndClrSerializationsRoundTripExactlyAndRejectNonNativeInput()throws Exception {
        String columns="(id int NOT NULL PRIMARY KEY,x xml NULL,h hierarchyid NULL,gm geometry NULL,gg geography NULL,v int NULL)";
        create("src",columns);create("dst",columns);
        String[] documents={"<a xmlns=\"urn:a\" xmlns:b=\"urn:b\"><b:c attr=\"&quot;q&apos;&amp;&lt;\">t&amp;x</b:c><!-- c --><?pi data?></a>",
                "<r><![CDATA[<not markup> & ]]></r>","<r xml:space=\"preserve\">  keep  <i> spaced </i>  </r>","<r>😀中&#x1F600;&#9;tab</r>","fragment <x/> tail"};
        try(var p=control.prepareStatement("INSERT INTO "+t("src")+"(id,x,v) VALUES(?,?,0)")){for(int i=0;i<documents.length;i++){p.setInt(1,i+1);p.setString(2,documents[i]);p.addBatch();}p.executeBatch();}
        sql(control,"UPDATE "+t("src")+" SET h=hierarchyid::Parse('/1/2/3.5/'),gm=geometry::STGeomFromText('POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))',3857),"
                +"gg=geography::STGeomFromText('LINESTRING(-122.360 47.656, -122.343 47.656)',4326) WHERE id=1");
        sql(control,"UPDATE "+t("src")+" SET h=hierarchyid::GetRoot(),gm=geometry::STGeomFromText('POINT EMPTY',0),gg=geography::Point(-89.9,179.9,4269) WHERE id=2");
        sql(control,"INSERT INTO "+t("src")+"(id,v) VALUES(9,NULL)");
        String canon="SELECT id,CAST(x AS nvarchar(max)),CONVERT(varchar(max),CAST(h AS varbinary(max)),1),CONVERT(varchar(max),CAST(gm AS varbinary(max)),1),"
                +"CONVERT(varchar(max),CAST(gg AS varbinary(max)),1),CAST(v AS varchar(9)) FROM %s ORDER BY id";
        assertSuccess(run(table("src","dst"),false));assertEquals(rows(canon,"src"),rows(canon,"dst"));
        sql(control,"UPDATE "+t("src")+" SET x='<changed a=\"1\"/>',gm=geometry::STGeomFromText('LINESTRING (1 1, 2 2)',3857),h=hierarchyid::Parse('/9/') WHERE id=1");
        sql(control,"UPDATE "+t("src")+" SET v=1 WHERE id=3");
        var diff=run(table("src","dst"),true);assertSuccess(diff);assertEquals(rows(canon,"src"),rows(canon,"dst"));
        var again=run(table("src","dst"),true);assertSuccess(again);assertEquals(0,again.getTableResultList().getFirst().getSyncCount().get());
        var writer=new SqlServerWriterImpl(ds());writer.prepareWrite(schema,"dst",List.of("id","gm"));
        assertThrows(RuntimeException.class,()->writer.write(List.of("id","gm"),Collections.singletonList(new Object[]{100,"POINT (1 2)"})));assertEquals(6,count("dst"));
    }

    @Test void writerDomainsRejectRoundingPaddingAndCodePageLossBeforeSending()throws Exception {
        create("dst","(id int NOT NULL PRIMARY KEY,m money NULL,ti tinyint NULL,d3 datetime2(3) NULL,dt datetime NULL,sdt smalldatetime NULL,b8 binary(8) NULL,"
                +"vc varchar(20) COLLATE Latin1_General_100_CI_AS NULL,o datetimeoffset(3) NULL,g uniqueidentifier NULL,t3 time(3) NULL)");
        var fields=List.of("id","m","ti","d3","dt","sdt","b8","vc","o","g","t3");
        Object[][] invalid={{1,new BigDecimal("1.23456"),null,null,null,null,null,null,null,null,null},{2,null,256,null,null,null,null,null,null,null,null},
                {3,null,-1,null,null,null,null,null,null,null,null},{4,null,null,LocalDateTime.parse("2026-10-04T12:34:56.1234"),null,null,null,null,null,null,null},
                {5,null,null,null,LocalDateTime.parse("2026-10-04T12:34:56.004"),null,null,null,null,null,null},{6,null,null,null,null,LocalDateTime.parse("2026-10-04T12:34:30"),null,null,null,null,null},
                {7,null,null,null,null,null,new byte[]{1,2,3},null,null,null,null},{8,null,null,null,null,null,null,"汉字",null,null,null},
                {9,null,null,null,null,null,null,null,OffsetDateTime.parse("2026-10-04T12:34:56.1234+05:30"),null,null},
                {10,null,null,null,null,null,null,null,Timestamp.valueOf("2026-10-04 12:34:56"),null,null},{11,null,null,null,null,null,null,null,null,new byte[16],null},
                {12,1.5d,null,null,null,null,null,null,null,null,null},{13,null,null,null,null,null,null,"x".repeat(21),null,null,null},
                {14,null,null,null,null,null,null,null,null,null,LocalTime.parse("12:34:56.0005")},{15,null,null,null,LocalDateTime.parse("1752-12-31T23:59:59"),null,null,null,null,null,null}};
        for(var row:invalid) {
            var writer=new SqlServerWriterImpl(ds());writer.prepareWrite(schema,"dst",fields);
            assertThrows(RuntimeException.class,()->writer.write(fields,Collections.singletonList(row)),()->"row "+row[0]);
        }
        assertEquals(0,count("dst"));
        var writer=new SqlServerWriterImpl(ds());writer.prepareWrite(schema,"dst",fields);
        writer.write(fields,Collections.singletonList(new Object[]{20,new BigDecimal("-922337203685477.5808"),(short)255,LocalDateTime.parse("1582-10-05T12:34:56.123"),
                LocalDateTime.parse("1753-01-01T00:00:00.007"),LocalDateTime.parse("2079-06-06T23:59"),new byte[]{1,2,3,4,5,6,7,8},"café",
                OffsetDateTime.parse("0001-01-01T00:00:00.123-14:00"),UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff"),LocalTime.parse("23:59:59.999")}));
        assertEquals(List.of(List.of("20","-922337203685477.5808","255","1582-10-05 12:34:56.123","1753-01-01 00:00:00.007","2079-06-06 23:59:00.000","0x0102030405060708",
                "café","0001-01-01 00:00:00.123 -14:00","6F9619FF-8B86-D011-B42D-00C04FC964FF","23:59:59.999")),
                rows("SELECT CAST(id AS varchar(9)),CONVERT(varchar(40),m,2),CAST(ti AS varchar(5)),CAST(d3 AS varchar(30)),CONVERT(varchar(30),dt,121),CONVERT(varchar(30),sdt,121),"
                        +"CONVERT(varchar(20),b8,1),vc,CAST(o AS varchar(40)),CAST(g AS char(36)),CAST(t3 AS varchar(20)) FROM %s","dst"));
    }

    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void sqlServerToPostgresRequiresPortableColumnsAndExactLossDecisionsBeforeFullAndDiff()throws Exception {
        openPostgres();
        create("src","(id int NOT NULL PRIMARY KEY,g uniqueidentifier NULL,m money NULL,sm smallmoney NULL,ti tinyint NULL,d date NULL,d2 datetime2(7) NULL,"
                +"dt datetime NULL,sdt smalldatetime NULL,o datetimeoffset(7) NULL,nv nvarchar(max) NULL,vb varbinary(max) NULL,rv rowversion)");
        sql(control,"INSERT INTO "+t("src")+"(id,g,m,sm,ti,d,d2,dt,sdt,o,nv,vb) VALUES(1,'6F9619FF-8B86-D011-B42D-00C04FC964FF',922337203685477.5807,214748.3647,255,"
                +"'1582-10-10','2026-10-04 12:34:56.123456','9999-12-31 23:59:59.997','2079-06-06 23:59','2026-10-04 12:34:56.123456 +05:30',"
                +"REPLICATE(CAST(N'汉😀' AS nvarchar(max)),30000),CAST(REPLICATE(CAST(0xCD AS varbinary(max)),50000) AS varbinary(max))),"
                +"(2,'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11',-922337203685477.5808,-214748.3648,0,'0001-01-01','0001-01-01 00:00:00','1753-01-01 00:00:00.003',"
                +"'1900-01-01','0001-01-01 00:00:00 -14:00',N'',0x),(3,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        pgSql("CREATE TABLE \""+schema+"\".dst(id integer PRIMARY KEY,g uuid,m numeric(19,4),sm numeric(10,4),ti smallint,d date,d2 timestamp(6),dt timestamp(3),"
                +"sdt timestamp(0),o timestamptz,nv text,vb bytea,rv bytea)");
        var request=table("src","dst");postgresTarget(request);request.getTarget().setWriterEngineInsert(true);
        var rejected=run(request,false);assertFailedBeforeWrites(rejected);
        assertEquals(List.of(),postgresValues());
        var blocked=rejected.getTableResultList().getFirst().getTypeMappingPlan().columns().stream()
                .filter(column->column.fidelity()==TypeFidelity.UNSUPPORTED).toList();
        assertEquals(Set.of("o","rv"),blocked.stream().map(column->column.column()).collect(java.util.stream.Collectors.toSet()));
        for(var column:blocked)assertThrows(IllegalArgumentException.class,()->LossyMappingAcknowledgement.forColumn(schema,"dst",column));
        acknowledgeLosses(request,rejected,"dst");
        assertFailedBeforeWrites(run(request,false));assertEquals(List.of(),postgresValues());
        request.getSource().getFirst().setRegexColumnMapper(List.of(new PatternMapper("^(o|rv)$","")));
        assertSuccess(run(request,false));assertEquals(sqlServerValues(),postgresValues());
        // The PostgreSQL reader returns java.sql.Date, which cannot hold 1582-10-05..14; keep DIFF comparisons outside that gap.
        sql(control,"UPDATE "+t("src")+" SET m=m-0.0001,ti=7,d='2026-10-04' WHERE id=1");
        var before=postgresValues();request.setTypeMappingAcknowledgements(List.of());
        var unconfirmed=run(request,true);assertFailedBeforeWrites(unconfirmed);assertEquals(before,postgresValues());
        acknowledgeLosses(request,unconfirmed,"dst");
        var diff=run(request,true);assertSuccess(diff);assertEquals(1,diff.getTableResultList().getFirst().getSyncCount().get());assertEquals(sqlServerValues(),postgresValues());
        var again=run(request,true);assertSuccess(again);assertEquals(0,again.getTableResultList().getFirst().getSyncCount().get());
        create("keys","(g uniqueidentifier NOT NULL PRIMARY KEY,v int NULL)");
        sql(control,"INSERT INTO "+t("keys")+" VALUES('6F9619FF-8B86-D011-B42D-00C04FC964FF',1),('A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11',2),('00000000-0000-0000-0000-000000000001',3)");
        pgSql("CREATE TABLE \""+schema+"\".keys(g uuid PRIMARY KEY,v integer)");
        var keys=table("keys","keys");postgresTarget(keys);keys.getTarget().setWriterEngineInsert(true);
        assertSuccess(run(keys,false));
        var unchanged=run(keys,true);assertSuccess(unchanged);assertEquals(0,unchanged.getTableResultList().getFirst().getSyncCount().get());
        sql(control,"UPDATE "+t("keys")+" SET v=20 WHERE v=2");
        var changed=run(keys,true);assertSuccess(changed);assertEquals(1,changed.getTableResultList().getFirst().getSyncCount().get());
    }

    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresToSqlServerUsesExactDomainsAndRejectsLossBeforeWrites()throws Exception {
        openPostgres();
        pgSql("CREATE TABLE \""+schema+"\".src(id integer PRIMARY KEY,g uuid,m numeric(19,4),sm numeric(10,4),ti smallint,d date,ts timestamp(6),dt timestamp(3),txt text,b bytea,n numeric(38,10))");
        pgSql("INSERT INTO \""+schema+"\".src VALUES(1,'6f9619ff-8b86-d011-b42d-00c04fc964ff',922337203685477.5807,-214748.3648,255,'2026-10-04','2026-10-04 12:34:56.123456',"
                +"'2026-10-04 12:34:56.997','汉😀''\\',decode('00ff10','hex'),-0.0000000001),(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        create("dst","(id int NOT NULL PRIMARY KEY,g uniqueidentifier NULL,m money NULL,sm smallmoney NULL,ti tinyint NULL,d date NULL,ts datetime2(7) NULL,dt datetime NULL,"
                +"txt nvarchar(max) NULL,b varbinary(max) NULL,n numeric(38,10) NULL)");
        var request=postgresSource("src","dst");var unconfirmed=run(request,false);
        assertFailedBeforeWrites(unconfirmed);assertEquals(0,count("dst"));
        acknowledgeLosses(request,unconfirmed,"dst");assertSuccess(run(request,false));
        assertEquals(List.of(List.of("1","6F9619FF-8B86-D011-B42D-00C04FC964FF","922337203685477.5807","-214748.3648","255","2026-10-04","2026-10-04 12:34:56.1234560",
                        "2026-10-04 12:34:56.997","汉😀'\\","0x00FF10","-0.0000000001"),Arrays.asList("2",null,null,null,null,null,null,null,null,null,null)),
                rows("SELECT CAST(id AS varchar(9)),CAST(g AS char(36)),CONVERT(varchar(40),m,2),CONVERT(varchar(40),sm,2),CAST(ti AS varchar(5)),CONVERT(char(10),d,23),"
                        +"CAST(ts AS varchar(30)),CONVERT(varchar(30),dt,121),txt,CONVERT(varchar(20),b,1),CAST(n AS varchar(50)) FROM %s ORDER BY id","dst"));
        pgSql("UPDATE \""+schema+"\".src SET m=1.0001,g='a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11' WHERE id=1");pgSql("INSERT INTO \""+schema+"\".src(id,ti) VALUES(3,7)");
        var diff=run(request,true);assertSuccess(diff);assertEquals(2,diff.getTableResultList().getFirst().getSyncCount().get());
        var again=run(request,true);assertSuccess(again);assertEquals(0,again.getTableResultList().getFirst().getSyncCount().get());
        String[][] rejected={{"numeric(20,6)","1.234567","money"},{"smallint","256","tinyint"},{"smallint","-1","tinyint"},{"timestamp(6)","'2026-10-04 12:34:56.123456'","datetime"},
                {"timestamp(6)","'2026-10-04 12:34:56.123456'","datetime2(3)"},{"text","'汉字'","varchar(10) COLLATE Latin1_General_100_CI_AS"},
                {"bytea","decode('010203','hex')","binary(8)"},{"timestamptz","'2026-10-04 12:34:56+00'","datetimeoffset(7)"},{"double precision","0.1","decimal(10,4)"}};
        for(int i=0;i<rejected.length;i++) {
            String name="r"+i;pgSql("CREATE TABLE \""+schema+"\"."+name+"(id integer PRIMARY KEY,v "+rejected[i][0]+")");pgSql("INSERT INTO \""+schema+"\"."+name+" VALUES(1,"+rejected[i][1]+")");
            create(name,"(id int NOT NULL PRIMARY KEY,v "+rejected[i][2]+" NULL)");
            var result=run(postgresSource(name,name),false);assertFailedBeforeWrites(result);assertEquals(0,count(name),String.join(" -> ",rejected[i]));
        }
    }

    @Test void cursorReplaysExtendedPayloadsAndOrdersGuidKeysByTheSqlServerComparator()throws Exception {
        pin=SqlServerSourceGeneration.initialize(control,generationSchema(),15);
        String columns="([WM] datetime NOT NULL,[K] uniqueidentifier NOT NULL PRIMARY KEY,[M] money NULL,[SM] smallmoney NULL,[O] datetimeoffset(7) NULL,"
                +"[DT] datetime NULL,[SDT] smalldatetime NULL,[TI] tinyint NULL,[NV] nvarchar(max) NULL,[D] date NULL)";
        create("S",columns);create("T",columns);
        String[] keys={"00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000001","FFFFFFFF-0000-0000-0000-000000000001",
                "00000000-0000-0000-0001-000000000001","00000000-FFFF-0000-0000-000000000001","12345678-9ABC-DEF0-1234-56789ABCDEF0"};
        for(int i=0;i<keys.length;i++)sql(control,"INSERT INTO "+t("S")+" VALUES('2026-10-04T00:00:00.003','"+keys[i]+"',"+(i%2==0?"922337203685477.5807":"-0.0001")
                +",214748.3647,'2026-10-04 12:34:56.1234567 +05:"+(i%2==0?"30":"45")+"','1753-01-01T00:00:00.007','2079-06-06T23:59:00',"+(250+i)+",REPLICATE(CAST(N'汉' AS nvarchar(max)),9000),'1582-10-1"+i+"')");
        sql(control,"INSERT INTO "+t("S")+"([WM],[K]) VALUES('2026-10-04T00:00:00.007','00000000-0000-0000-0000-0000000000FF')");
        List<String> sqlServerOrder=new ArrayList<>();try(var s=control.createStatement();var r=s.executeQuery("SELECT CAST([K] AS char(36)) FROM "+t("S")+" ORDER BY [WM],[K]")){while(r.next())sqlServerOrder.add(r.getString(1).toLowerCase(Locale.ROOT));}
        var textOrder=new ArrayList<>(sqlServerOrder);Collections.sort(textOrder);assertNotEquals(textOrder,sqlServerOrder);
        var fields=List.of("WM","K","M","SM","O","DT","SDT","TI","NV","D");var limits=new BatchLimits(2,1<<20,1<<19,2000,1000);
        var reader=new SqlServerDatabaseOperator(ds());reader.setFetchSize(1);List<String> observed=new ArrayList<>();
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"S",fields,"WM",List.of("K"),null,null,null,limits,false,pin))) {
            while(cursor.next()){observed.add(cursor.row()[1].toString());assertEquals(observed.getLast(),cursor.position().values().get(1).value());}
        }
        assertEquals(sqlServerOrder,observed);
        var calls=new AtomicInteger();
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(IllegalStateException.class,()->CursorTransfer.execute(runtime,ds(),ds(),ProductTypeEnum.SQLSERVER,ProductTypeEnum.SQLSERVER,schema,"S",schema,"T",
                    fields,fields,List.of("K"),List.of("K"),"WM",limits,2,new CursorRunBinding("e".repeat(64),saved.get(),value->calls.incrementAndGet()!=3&&save(value),pin),new DbSwitchTableResult(),()->{}));
            assertFalse(saved.get().complete());
            CursorTransfer.execute(runtime,ds(),ds(),ProductTypeEnum.SQLSERVER,ProductTypeEnum.SQLSERVER,schema,"S",schema,"T",fields,fields,List.of("K"),List.of("K"),"WM",limits,2,
                    new CursorRunBinding("e".repeat(64),saved.get(),this::save,pin),new DbSwitchTableResult(),()->{});
            assertTrue(saved.get().complete());
        }
        String canon="SELECT CONVERT(varchar(30),[WM],121),CAST([K] AS char(36)),CONVERT(varchar(40),[M],2),CONVERT(varchar(40),[SM],2),CAST([O] AS varchar(40)),"
                +"CONVERT(varchar(30),[DT],121),CONVERT(varchar(30),[SDT],121),CAST([TI] AS varchar(5)),CONVERT(varchar(64),HASHBYTES('SHA2_256',[NV]),2),CONVERT(char(10),[D],23) FROM %s ORDER BY [K]";
        assertEquals(rows(canon,"S"),rows(canon,"T"));
    }

    @Test void cursorRejectsOffsetKeysAndUnverifiedTypesBeforeReading()throws Exception {
        pin=SqlServerSourceGeneration.initialize(control,generationSchema(),15);var limits=new BatchLimits(2,1<<20,1<<19,2000,1000);
        Map<String,String> tables=new LinkedHashMap<>();
        tables.put("offsetkey","([WM] bigint NOT NULL,[K] datetimeoffset(7) NOT NULL PRIMARY KEY)");
        tables.put("xmlpayload","([WM] bigint NOT NULL,[K] bigint NOT NULL PRIMARY KEY,[X] xml NULL)");
        tables.put("clrpayload","([WM] bigint NOT NULL,[K] bigint NOT NULL PRIMARY KEY,[H] hierarchyid NULL)");
        tables.put("variantpayload","([WM] bigint NOT NULL,[K] bigint NOT NULL PRIMARY KEY,[V] sql_variant NULL)");
        tables.put("computed","([WM] bigint NOT NULL,[K] bigint NOT NULL PRIMARY KEY,[C] AS ([K]*2) PERSISTED)");
        for(var table:tables.entrySet()) {
            create(table.getKey(),table.getValue());var reader=new SqlServerDatabaseOperator(ds());
            assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,table.getKey(),List.of("WM","K"),"WM",List.of("K"),null,null,null,limits,false,pin)),table.getKey());
        }
    }

    @Test @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void moneyCursorKeysReplayIntoPostgresNumericAndNarrowerTargetsAreRejected()throws Exception {
        openPostgres();pin=SqlServerSourceGeneration.initialize(control,generationSchema(),15);
        create("S","([WM] smalldatetime NOT NULL,[K] money NOT NULL PRIMARY KEY,[V] uniqueidentifier NULL,[O] datetimeoffset(6) NULL)");
        sql(control,"INSERT INTO "+t("S")+" VALUES('2026-10-04T12:00:00',922337203685477.5807,'6F9619FF-8B86-D011-B42D-00C04FC964FF','2026-10-04 12:34:56.123456 +05:30'),"
                +"('2026-10-04T12:01:00',-922337203685477.5808,NULL,NULL),('2026-10-04T12:01:00',0.0001,'00000000-0000-0000-0000-000000000001','0001-01-01 00:00:00 -14:00')");
        pgSql("CREATE TABLE \""+schema+"\".\"T\"(\"WM\" timestamp(0) NOT NULL,\"K\" numeric(19,4) PRIMARY KEY,\"V\" uuid,\"O\" timestamptz)");
        pgSql("CREATE TABLE \""+schema+"\".\"N\"(\"WM\" timestamp(0) NOT NULL,\"K\" numeric(18,4) PRIMARY KEY,\"V\" uuid,\"O\" timestamptz)");
        var result=run(cursorRequest("T"),false);assertSuccess(result);assertTrue(saved.get().complete());
        try(var s=pg.createStatement();var r=s.executeQuery("SELECT \"K\",\"V\",\"O\" FROM \""+schema+"\".\"T\" ORDER BY \"K\"")) {
            assertTrue(r.next());assertEquals(new BigDecimal("-922337203685477.5808"),r.getBigDecimal(1));assertNull(r.getObject(2));
            assertTrue(r.next());assertEquals(new BigDecimal("0.0001"),r.getBigDecimal(1));assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"),r.getObject(2));
            assertEquals(OffsetDateTime.parse("0001-01-01T14:00:00Z").toInstant(),r.getObject(3,OffsetDateTime.class).toInstant());
            assertTrue(r.next());assertEquals(new BigDecimal("922337203685477.5807"),r.getBigDecimal(1));assertEquals(UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff"),r.getObject(2));
            assertEquals(OffsetDateTime.parse("2026-10-04T12:34:56.123456+05:30").toInstant(),r.getObject(3,OffsetDateTime.class).toInstant());assertFalse(r.next());
        }
        saved.set(null);assertFalse(run(cursorRequest("N"),false).getIfAllSuccess().get());
        try(var s=pg.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM \""+schema+"\".\"N\"")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }

    private void seedTyped(String table)throws Exception {
        sql(control,"INSERT INTO "+t(table)+" VALUES(1,922337203685477.5807,214748.3647,255,'6F9619FF-8B86-D011-B42D-00C04FC964FF','0001-01-01','23:59:59.9999999',"
                +"'9999-12-31T23:59:59.9999999','9999-12-31T23:59:59.999','0001-01-01 00:00:00 -14:00','9999-12-31 23:59:59.9999999 +14:00','9999-12-31T23:59:59.997',"
                +"'2079-06-06T23:59:00',REPLICATE(CAST(N'汉😀' AS nvarchar(max)),150000),REPLICATE(CAST('x' AS varchar(max)),100000),"
                +"CAST(REPLICATE(CAST(0xAB AS varbinary(max)),200000) AS varbinary(max)),9999999999999999999999999999.9999999999)");
        sql(control,"INSERT INTO "+t(table)+" VALUES(2,-922337203685477.5808,-214748.3648,0,'00000000-0000-0000-0000-000000000000','9999-12-31','00:00:00.0000001',"
                +"'0001-01-01T00:00:00.0000001','0001-01-01T00:00:00.001','2026-10-04 00:00:00 +00:00','0001-01-01 00:00:00.0000001 -14:00','1753-01-01T00:00:00.003',"
                +"'1900-01-01T00:00:00',N'',CAST(N'café' AS varchar(10)),0x,-9999999999999999999999999999.9999999999)");
        sql(control,"INSERT INTO "+t(table)+" VALUES(3,0.0001,-0.0001,128,'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11','1582-10-10','12:34:56.1234567',"
                +"'1582-10-05T12:34:56.1234567','1582-10-14T01:02:03.004','1582-10-04 12:00:00 -01:00','2026-03-08 02:30:00.1234567 -05:00','2026-10-04T12:34:29.997',"
                +"'2026-10-04T12:34:00',N'中文''\\',CAST('a' AS varchar(1)),0x00,0.0000000001)");
        sql(control,"INSERT INTO "+t(table)+"(id) VALUES(4)");
    }
    private List<List<String>> canon(String table)throws Exception {return rows(CANON,table);}
    private List<List<Object>> sqlServerValues()throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var s=control.createStatement();var r=s.executeQuery("SELECT id,g,m,sm,ti,d,d2,dt,sdt,nv,vb FROM "+t("src")+" ORDER BY id")) {
            while(r.next())result.add(Arrays.asList(r.getInt(1),r.getString(2)==null?null:UUID.fromString(r.getString(2)),r.getBigDecimal(3),r.getBigDecimal(4),r.getObject(5)==null?null:r.getInt(5),
                    r.getObject(6,LocalDate.class),r.getObject(7,LocalDateTime.class),r.getObject(8,LocalDateTime.class),r.getObject(9,LocalDateTime.class),
                    digest(r.getString(10)),digest(r.getBytes(11))));
        }
        return result;
    }
    private List<List<Object>> postgresValues()throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var s=pg.createStatement();var r=s.executeQuery("SELECT id,g,m,sm,ti,d,d2,dt,sdt,nv,vb FROM \""+schema+"\".dst ORDER BY id")) {
            while(r.next())result.add(Arrays.asList(r.getInt(1),r.getObject(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getObject(5)==null?null:r.getInt(5),
                    r.getObject(6,LocalDate.class),r.getObject(7,LocalDateTime.class),r.getObject(8,LocalDateTime.class),r.getObject(9,LocalDateTime.class),
                    digest(r.getString(10)),digest(r.getBytes(11))));
        }
        return result;
    }
    private void acknowledgeLosses(MigrationRequest request,DbSwitchResult rejected,String targetTable) {
        request.setTypeMappingAcknowledgements(rejected.getTableResultList().getFirst().getTypeMappingPlan().columns().stream()
                .filter(column->column.fidelity()==TypeFidelity.LOSSY)
                .map(column->LossyMappingAcknowledgement.forColumn(schema,targetTable,column)).toList());
        assertFalse(request.getTypeMappingAcknowledgements().isEmpty());
    }
    private static String digest(Object value)throws Exception {
        if(value==null)return null;byte[] bytes=value instanceof byte[] b?b:value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return bytes.length+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private MigrationRequest table(String sourceTable,String targetTable) {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.SQLSERVER);source.setUrl(url);source.setUsername("sa");source.setPassword(password());
        source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.SQLSERVER);target.setUrl(url);target.setUsername("sa");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);return request;
    }
    private void postgresTarget(MigrationRequest request) {
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.POSTGRESQL);target.setUrl(pgUrl());target.setUsername(pgUser());target.setPassword(pgPassword());
    }
    private MigrationRequest postgresSource(String sourceTable,String targetTable) {
        var request=table(sourceTable,targetTable);var source=request.getSource().getFirst();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);source.setUrl(pgUrl());source.setUsername(pgUser());source.setPassword(pgPassword());return request;
    }
    private MigrationRequest cursorRequest(String target) {
        var request=table("S",target);postgresTarget(request);var source=request.getSource().getFirst();
        source.setIncreaseColumnName("WM");source.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        request.getTarget().setChangeDataSyncType("INCREASE_COLUMN");request.getTarget().setCursorRunBindings(Map.of("S",new CursorRunBinding("e".repeat(64),saved.get(),this::save,pin)));
        return request;
    }
    private DbSwitchResult run(MigrationRequest request,boolean diff)throws Exception {
        var target=request.getTarget();target.setChangeDataSync(diff||"INCREASE_COLUMN".equals(target.getChangeDataSyncType()));
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private boolean save(CursorCheckpoint value){saved.set(value);return true;}
    private String generationSchema()throws SQLException{sql(control,"CREATE SCHEMA "+q(generationSchema));return generationSchema;}
    private void openPostgres()throws Exception {Class.forName("org.postgresql.Driver");pg=DriverManager.getConnection(pgUrl(),pgUser(),pgPassword());pgSql("CREATE SCHEMA \""+schema+"\"");}
    private void pgSql(String text)throws SQLException{sql(pg,text);}
    private DataSource ds(){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,"sa",password());}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Fixture principal is fixed");}};}
    private List<List<String>> rows(String query,String table)throws Exception {
        var result=new ArrayList<List<String>>();
        try(var s=control.createStatement();var r=s.executeQuery(String.format(query,t(table)))){while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));result.add(row);}}
        return result;
    }
    private long count(String table)throws SQLException{try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private void create(String table,String columns)throws SQLException{sql(control,"CREATE TABLE "+t(table)+columns);}
    private String t(String name){return q(schema)+'.'+q(name);}
    private static void sql(Connection c,String text)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute(text);}}
    private static String q(String name){return '['+name.replace("]","]]")+']';}
    private static String password(){return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD");}
    private static String pgUrl(){return System.getProperty("datahub.test.postgres.url");}
    private static String pgUser(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String pgPassword(){return System.getProperty("datahub.test.postgres.password","");}
    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
    }
    private static void assertFailedBeforeWrites(DbSwitchResult result) {
        assertFalse(result.getIfAllSuccess().get());var table=result.getTableResultList().getFirst();
        assertTrue(table.getErrorMsg()!=null&&table.getErrorMsg().contains("externalWriteUnconfirmed=false"),table::getErrorMsg);
    }
}
