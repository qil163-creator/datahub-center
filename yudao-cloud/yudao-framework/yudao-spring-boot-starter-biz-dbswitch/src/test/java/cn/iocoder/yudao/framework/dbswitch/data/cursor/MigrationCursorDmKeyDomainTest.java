package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.dm.DmCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real DM8 cursor key domains: every page boundary is a DM bound comparison, never Java ordering. */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class MigrationCursorDmKeyDomainTest {
    private static final ProductTypeEnum DM=ProductTypeEnum.DM,PG=ProductTypeEnum.POSTGRESQL;
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private String schema;private Connection dm,pg;
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private SourceGenerationPin pin;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        dm=open(DM);pg=open(PG);schema="DM_KEYS_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql(dm,"CREATE SCHEMA "+q(schema));sql(pg,"CREATE SCHEMA "+q(schema));pin=new SourceGenerationPin(schema,UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    @AfterEach void cleanup()throws Exception {try{if(pg!=null)try(var c=pg){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{try{if(dm!=null)try(var c=dm){sql(c,"DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}}

    @Test void textKeysFollowDmCodePointOrderAndKeepTrailingSpaceTextExactly()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"K\" VARCHAR(12 CHAR) NOT NULL PRIMARY KEY,\"V\" INT)");
        // U+1F600 precedes U+E000 in Java UTF-16 order but follows it in DM code-point order; 'z  ' and '中文 ' keep their spaces.
        var keys=List.of("","!","0","A","B","a","a\tb","a b","ab","abcdefghijkl","一二三四五六七八九十百千","中","中文 ","é","\uE000","\uFFFD","\uD83D\uDE00","汉字\uD83D\uDE42","z  ");
        int n=0;for(String k:keys)insert(dm,t("S"),k,n++);
        var result=transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save);
        assertEquals(keys.size(),result.getSyncCount().get());assertEquals(text(dm,"S"),text(dm,"T"));
        assertEquals("\uD83D\uDE00",saved.get().applied().values().getFirst().value());assertEquals("VARCHAR(12 CHAR)",saved.get().fixedUpper().values().getFirst().typeName());
        insert(dm,t("S"),"\uD83D\uDE01",99);transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save);assertEquals(text(dm,"S"),text(dm,"T"));assertTrue(saved.get().complete());
        // A whole-window replay matches every exact variant instead of duplicating or rewriting keys.
        saved.set(null);assertEquals(keys.size()+1,transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save).getSyncCount().get());
        assertEquals(text(dm,"S"),text(dm,"T"));assertEquals(keys.size()+1,count(dm,"T"));
    }
    @Test void targetSpaceVariantFailsClosedInsteadOfBeingAdopted()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"K\" VARCHAR(10 CHAR) NOT NULL PRIMARY KEY,\"V\" INT)");
        insert(dm,t("S"),"a",1);insert(dm,t("S"),"b",2);
        // DM key equality ignores trailing U+0020: an existing 'b ' is the same key as 'b' but not the same text.
        insert(dm,t("T"),"b ",9);assertThrows(SQLException.class,()->insert(dm,t("T"),"b",8));
        assertThrows(UnconfirmedWriteException.class,()->transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save));
        assertEquals(List.of("b |9"),text(dm,"T"));assertNull(saved.get().applied());assertEquals(0,saved.get().sequence());
        sql(dm,"DELETE FROM "+t("T"));transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save);assertEquals(text(dm,"S"),text(dm,"T"));
    }
    @Test void charKeysReplayDmPaddedValuesExactly()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"K\" CHAR(4) NOT NULL PRIMARY KEY,\"V\" INT)");
        int n=0;for(String k:List.of("a","a\t","ab","abcd","中",""))insert(dm,t("S"),k,n++);
        transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save);
        assertEquals(text(dm,"S"),text(dm,"T"));assertEquals(6,count(dm,"T"));assertTrue(text(dm,"T").contains("中 |4"));
        saved.set(null);assertEquals(6,transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save).getSyncCount().get());assertEquals(text(dm,"S"),text(dm,"T"));assertEquals(6,count(dm,"T"));
    }
    @Test void columnCollateClauseCannotMakeThisServerCaseInsensitive()throws Exception {
        // DM 8.1.5.60 parses COLLATE BINARY_CI, records nothing in its catalog and keeps the database binary profile.
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"K\" VARCHAR(10) COLLATE BINARY_CI NOT NULL PRIMARY KEY,\"V\" INT)");
        int n=0;for(String k:List.of("b","a","B","A","é","E"))insert(dm,t("S"),k,n++);
        assertEquals(6,transfer(DM,DM,List.of("K","V"),List.of("K"),"K",this::save).getSyncCount().get());
        assertEquals(text(dm,"S"),text(dm,"T"));assertEquals(List.of("A","B","E","a","b","é"),strings(dm,"SELECT \"K\" FROM "+t("T")+" ORDER BY \"K\""));
    }
    @ParameterizedTest @ValueSource(strings={"Asia/Shanghai","America/New_York"})
    void temporalKeysPageOnEveryComponentWithNanosecondsAndDstGaps(String zone)throws Exception {
        var previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"TS\" TIMESTAMP(9) NOT NULL,\"TS6\" TIMESTAMP(6) NOT NULL,\"D\" DATE NOT NULL,\"T\" TIME(6) NOT NULL,\"V\" INT,PRIMARY KEY(\"TS\",\"TS6\",\"D\",\"T\"))");
            var rows=temporalRows();int n=0;for(var r:rows)insert(dm,t("S"),r[0],r[1],r[2],r[3],n++);
            var expected=new ArrayList<String>();n=0;for(var r:rows)expected.add(r[0]+"|"+r[1]+"|"+r[2]+"|"+r[3]+"|"+n++);assertEquals(expected,temporal(dm,"S"));
            var fields=List.of("TS","TS6","D","T","V");var keys=List.of("TS","TS6","D","T");
            // Pages of two split ties on TS, TS6, D and T in turn; a skipped or re-read row changes the acknowledged count.
            assertEquals(rows.size(),transfer(DM,DM,fields,keys,"TS",this::save).getSyncCount().get());
            assertEquals(temporal(dm,"S"),temporal(dm,"T"));assertEquals("9999-12-31T23:59:59.999999999",saved.get().fixedUpper().values().getFirst().value());
            var last=rows.getLast()[0];
            insert(dm,t("S"),last,LocalDateTime.parse("0001-01-01T00:00"),LocalDate.parse("0001-01-01"),LocalTime.MIDNIGHT,90);
            insert(dm,t("S"),last,LocalDateTime.parse("9999-12-31T23:59:59.999999"),LocalDate.parse("9999-12-31"),LocalTime.parse("23:59:59.999998"),91);
            // Equal watermarks arriving later are rewound with the last group, whatever their remaining key order.
            assertEquals(3,transfer(DM,DM,fields,keys,"TS",this::save).getSyncCount().get());assertEquals(temporal(dm,"S"),temporal(dm,"T"));
            saved.set(null);assertEquals(rows.size()+2,transfer(DM,DM,fields,keys,"TS",this::save).getSyncCount().get());
            assertEquals(temporal(dm,"S"),temporal(dm,"T"));assertEquals(rows.size()+2,count(dm,"T"));
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void datetimeCompositeKeyPagesAndResumesBetweenDmSchemas()throws Exception {
        // DM catalogs DATETIME apart from TIMESTAMP although both hold the same local date-time domain.
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"DT\" DATETIME(9) NOT NULL,\"ID\" BIGINT NOT NULL,\"P\" DATETIME(6),\"V\" INT,PRIMARY KEY(\"DT\",\"ID\"))");
        var dt1=LocalDateTime.parse("1990-04-15T02:30:00.000000001");var dt2=LocalDateTime.parse("2026-01-02T03:04:05.123456788");var dt3=dt2.plusNanos(1);
        insert(dm,t("S"),dt1,2L,LocalDateTime.parse("1990-04-15T02:30:00.000001"),1);sql(dm,"INSERT INTO "+t("S")+" VALUES(TO_TIMESTAMP('1990-04-15 02:30:00.000000001','YYYY-MM-DD HH24:MI:SS.FF9'),1,NULL,2)");
        // A TIMESTAMP literal or CAST(... AS TIMESTAMP(9)) would round the ninth digit away on DM 8.1.5.60.
        assertEquals(List.of("1990-04-15 02:30:00.000000001","1990-04-15 02:30:00.000000001"),strings(dm,"SELECT TO_CHAR(\"DT\",'YYYY-MM-DD HH24:MI:SS.FF9') FROM "+t("S")+" ORDER BY \"ID\""));
        insert(dm,t("S"),dt2,7L,LocalDateTime.parse("2026-01-02T03:04:05.123456"),3);insert(dm,t("S"),dt3,Long.MIN_VALUE,LocalDateTime.parse("9999-12-31T23:59:59.999999"),4);
        insert(dm,t("S"),dt3,Long.MAX_VALUE,LocalDateTime.parse("0001-01-01T00:00"),5);
        var fields=List.of("DT","ID","P","V");var keys=List.of("DT","ID");var calls=new AtomicInteger();String query="SELECT \"DT\",\"ID\",\"P\",\"V\" FROM %s ORDER BY \"DT\",\"ID\"";
        // The second committed batch loses its checkpoint: the resumed frozen window replays it without duplicates.
        assertThrows(IllegalStateException.class,()->transfer(DM,DM,fields,keys,"DT",next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(dm,"T"));assertEquals(1,saved.get().sequence());
        assertEquals(5,transfer(DM,DM,fields,keys,"DT",this::save).getSyncCount().get());assertTrue(saved.get().complete());
        assertEquals(rows(dm,query.formatted(t("S")),LocalDateTime.class,BigDecimal.class,LocalDateTime.class,BigDecimal.class),rows(dm,query.formatted(t("T")),LocalDateTime.class,BigDecimal.class,LocalDateTime.class,BigDecimal.class));
        insert(dm,t("S"),dt3,0L,LocalDateTime.parse("2024-03-10T02:30"),6);
        assertEquals(3,transfer(DM,DM,fields,keys,"DT",this::save).getSyncCount().get());assertEquals("DATETIME(9)",saved.get().applied().values().getFirst().typeName());
        assertEquals(rows(dm,query.formatted(t("S")),LocalDateTime.class,BigDecimal.class,LocalDateTime.class,BigDecimal.class),rows(dm,query.formatted(t("T")),LocalDateTime.class,BigDecimal.class,LocalDateTime.class,BigDecimal.class));
    }
    @Test void datetimeWatermarkAndPayloadReachPostgresExactly()throws Exception {
        sql(dm,"CREATE TABLE "+t("S")+"(\"ID\" BIGINT NOT NULL PRIMARY KEY,\"DT\" DATETIME(6) NOT NULL,\"P\" DATETIME(6),\"V\" INT)");
        sql(pg,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT NOT NULL PRIMARY KEY,\"DT\" TIMESTAMP(6) NOT NULL,\"P\" TIMESTAMP(6),\"V\" INT)");
        var w1=LocalDateTime.parse("1990-04-15T02:30:00.000001");var w2=LocalDateTime.parse("2024-03-10T02:30");
        insert(dm,t("S"),3L,w1,w2,1);insert(dm,t("S"),1L,w1,LocalDateTime.parse("0001-01-01T00:00"),2);insert(dm,t("S"),2L,w2,LocalDateTime.parse("9999-12-31T23:59:59.999999"),3);
        sql(dm,"INSERT INTO "+t("S")+" VALUES(-1,TIMESTAMP '2024-03-10 02:30:00',NULL,4)");
        var fields=List.of("ID","DT","P","V");String query="SELECT \"ID\",\"DT\",\"P\",\"V\" FROM %s ORDER BY \"ID\"";Class<?>[] types={BigDecimal.class,LocalDateTime.class,LocalDateTime.class,BigDecimal.class};
        assertEquals(4,transfer(DM,PG,fields,List.of("ID"),"DT",this::save).getSyncCount().get());assertEquals(rows(dm,query.formatted(t("S")),types),rows(pg,query.formatted(t("T")),types));
        insert(dm,t("S"),0L,w2,w1,5);
        assertEquals(3,transfer(DM,PG,fields,List.of("ID"),"DT",this::save).getSyncCount().get());assertEquals(rows(dm,query.formatted(t("S")),types),rows(pg,query.formatted(t("T")),types));
    }
    @ParameterizedTest @ValueSource(strings={"WM6","D","T","NAME"})
    void nonNumericWatermarkWithBigintKeyIsExactFromDmToPostgres(String watermark)throws Exception {
        sql(dm,"CREATE TABLE "+t("S")+"(\"ID\" BIGINT NOT NULL PRIMARY KEY,\"WM6\" TIMESTAMP(6) NOT NULL,\"D\" DATE NOT NULL,\"T\" TIME(6) NOT NULL,\"NAME\" VARCHAR(20 CHAR) NOT NULL)");
        sql(pg,"CREATE TABLE "+t("T")+"(\"ID\" BIGINT NOT NULL PRIMARY KEY,\"WM6\" TIMESTAMP(6) NOT NULL,\"D\" DATE NOT NULL,\"T\" TIME(6) NOT NULL,\"NAME\" VARCHAR(20) NOT NULL)");
        Object[][] values={{1L,LocalDateTime.parse("1990-04-15T02:30:00.000001"),LocalDate.parse("1990-04-15"),LocalTime.parse("02:30:00.000001"),"中文"},
                {-9223372036854775808L,LocalDateTime.parse("0001-01-01T00:00"),LocalDate.parse("0001-01-01"),LocalTime.parse("00:00"),""},
                {9223372036854775807L,LocalDateTime.parse("9999-12-31T23:59:59.999999"),LocalDate.parse("9999-12-31"),LocalTime.parse("23:59:59.999999"),"\uD83D\uDE00"},
                {7L,LocalDateTime.parse("1990-04-15T02:30:00.000001"),LocalDate.parse("1990-04-15"),LocalTime.parse("02:30:00.000001"),"中文"},
                {8L,LocalDateTime.parse("2024-02-29T12:00:00.5"),LocalDate.parse("2024-02-29"),LocalTime.parse("12:00:00.5"),"a b"}};
        for(Object[] r:values)insert(dm,t("S"),r);
        var fields=List.of("ID","WM6","D","T","NAME");
        transfer(DM,PG,fields,List.of("ID"),watermark,this::save);
        assertEquals(mixed(dm,"S"),mixed(pg,"T"));assertTrue(saved.get().complete());
        // An equal watermark arriving later is rewound into the next window, never skipped.
        var late=values[2].clone();late[0]=6L;insert(dm,t("S"),late);
        transfer(DM,PG,fields,List.of("ID"),watermark,this::save);assertEquals(mixed(dm,"S"),mixed(pg,"T"));
    }
    @ParameterizedTest @CsvSource(delimiter=';',quoteCharacter='"',value={"VARCHAR(10 CHAR);VARCHAR(10);'a'","TIMESTAMP(9);TIMESTAMP(6);TIMESTAMP '2026-01-02 03:04:05'","DATE;DATE;DATE '2024-02-29'",
            "TIME(6);TIME(6);TIME '12:00:00'","DATETIME(6);TIMESTAMP(6);TIMESTAMP '2026-01-02 03:04:05'"})
    void nonNumericPrimaryKeysAreRejectedAcrossProductsBeforeWrites(String dmType,String pgType,String literal)throws Exception {
        sql(dm,"CREATE TABLE "+t("S")+"(\"K\" "+dmType+" NOT NULL PRIMARY KEY,\"V\" INT)");sql(pg,"CREATE TABLE "+t("T")+"(\"K\" "+pgType+" NOT NULL PRIMARY KEY,\"V\" INT)");
        sql(dm,"INSERT INTO "+t("S")+" VALUES("+literal+",1)");
        // Native text/temporal key equality is proven only between DM sessions sharing one comparison profile.
        var failure=assertThrows(IllegalArgumentException.class,()->transfer(DM,PG,List.of("K","V"),List.of("K"),"K",this::save));
        assertEquals("Source and target cursor keys do not have a verified lossless equality mapping",failure.getMessage());
        assertEquals(0,count(pg,"T"));assertNull(saved.get());
    }
    @ParameterizedTest @ValueSource(strings={"DM","POSTGRESQL"})
    void exactNumericBoundaryAndMixedCompositeKeys(ProductTypeEnum target)throws Exception {
        String max="99999999999999999999999999999999999999";
        sql(dm,"CREATE TABLE "+t("S")+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" NUMERIC(38,0) NOT NULL,\"K2\" BIGINT NOT NULL,\"K3\" NUMBER(20,0) NOT NULL,\"V\" VARCHAR(20),PRIMARY KEY(\"K1\",\"K2\",\"K3\"))");
        sql(control(target),"CREATE TABLE "+t("T")+"(\"WM\" DECIMAL(38,18) NOT NULL,\"K1\" NUMERIC(38,0) NOT NULL,\"K2\" BIGINT NOT NULL,\"K3\" "+(target==DM?"NUMBER(20,0)":"NUMERIC(20,0)")+" NOT NULL,\"V\" VARCHAR(20),PRIMARY KEY(\"K1\",\"K2\",\"K3\"))");
        String[][] rows={{"-0.000000000000000001","-"+max,"-9223372036854775808","-99999999999999999999","a"},{"-0.000000000000000001","-9223372036854775809","9223372036854775807","0","b"},
                {"-0.000000000000000001","9223372036854775808","-1","99999999999999999999","c"},{"0",max,"0","1","d"},{"0",max,"1","-1","e"}};
        for(String[] r:rows)insert(dm,t("S"),new BigDecimal(r[0]),new BigDecimal(r[1]),Long.parseLong(r[2]),new BigDecimal(r[3]),r[4]);
        var fields=List.of("WM","K1","K2","K3","V");
        transfer(DM,target,fields,List.of("K1","K2","K3"),"WM",this::save);assertEquals(numeric(dm,"S"),numeric(control(target),"T"));
        saved.set(null);transfer(DM,target,fields,List.of("K1","K2","K3"),"WM",this::save);assertEquals(numeric(dm,"S"),numeric(control(target),"T"));
    }
    @ParameterizedTest @ValueSource(strings={"DM","POSTGRESQL"})
    void fractionalDecimalKeysTieAndPageExactly(ProductTypeEnum target)throws Exception {
        sql(dm,"CREATE TABLE "+t("S")+"(\"WM\" DECIMAL(5,0) NOT NULL,\"K\" DECIMAL(20,10) NOT NULL PRIMARY KEY,\"V\" VARCHAR(10))");
        sql(control(target),"CREATE TABLE "+t("T")+"(\"WM\" DECIMAL(5,0) NOT NULL,\"K\" DECIMAL(20,10) NOT NULL PRIMARY KEY,\"V\" VARCHAR(10))");
        String[][] values={{"1","-9999999999.9999999999","a"},{"1","-0.0000000002","b"},{"1","-0.0000000001","c"},{"2","0","d"},{"2","0.0000000001","e"},{"2","9999999999.9999999999","f"}};
        for(String[] v:values)insert(dm,t("S"),new BigDecimal(v[0]),new BigDecimal(v[1]),v[2]);
        var fields=List.of("WM","K","V");String query="SELECT \"WM\",\"K\",\"V\" FROM %s ORDER BY \"K\"";Class<?>[] types={BigDecimal.class,BigDecimal.class,String.class};
        assertEquals(6,transfer(DM,target,fields,List.of("K"),"WM",this::save).getSyncCount().get());assertEquals(rows(dm,query.formatted(t("S")),types),rows(control(target),query.formatted(t("T")),types));
        insert(dm,t("S"),new BigDecimal("2"),new BigDecimal("0.0000000002"),"late");
        assertEquals(4,transfer(DM,target,fields,List.of("K"),"WM",this::save).getSyncCount().get());assertEquals(rows(dm,query.formatted(t("S")),types),rows(control(target),query.formatted(t("T")),types));
        saved.set(null);assertEquals(7,transfer(DM,target,fields,List.of("K"),"WM",this::save).getSyncCount().get());assertEquals(rows(dm,query.formatted(t("S")),types),rows(control(target),query.formatted(t("T")),types));
    }
    @Test void mixedTextBigintTimestampKeyResumesAfterCheckpointLoss()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"WM\" DECIMAL(10,0) NOT NULL,\"NAME\" VARCHAR(20 CHAR) NOT NULL,\"ID\" BIGINT NOT NULL,\"TS\" TIMESTAMP(9) NOT NULL,\"V\" VARCHAR(20),PRIMARY KEY(\"NAME\",\"ID\",\"TS\"))");
        var ten=BigDecimal.TEN;
        insert(dm,t("S"),ten,"",-1L,LocalDateTime.parse("1990-04-15T02:30:00.000000001"),"r1");insert(dm,t("S"),ten,"中文",-1L,LocalDateTime.parse("2026-01-02T03:04:05.123456788"),"r2");
        insert(dm,t("S"),ten,"中文",-1L,LocalDateTime.parse("2026-01-02T03:04:05.123456789"),"r3");insert(dm,t("S"),ten,"中文",1L,LocalDateTime.parse("0001-01-01T00:00"),"r4");
        insert(dm,t("S"),ten,"中文",2L,LocalDateTime.parse("0001-01-01T00:00"),"r5");insert(dm,t("S"),ten,"\uD83D\uDE00",0L,LocalDateTime.parse("9999-12-31T23:59:59.999999999"),"r6");
        var fields=List.of("WM","NAME","ID","TS","V");var keys=List.of("NAME","ID","TS");var calls=new AtomicInteger();
        // Pages split a (NAME,ID) tie by one nanosecond and a NAME tie by ID; the checkpoint after the second commit is lost.
        assertThrows(IllegalStateException.class,()->transfer(DM,DM,fields,keys,"WM",next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(dm,"T"));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());
        insert(dm,t("S"),ten,"A",0L,LocalDateTime.parse("2024-03-10T02:30"),"late equal");insert(dm,t("S"),BigDecimal.valueOf(11),"next",1L,LocalDateTime.parse("2024-03-10T02:30"),"next window");
        assertEquals(7,transfer(DM,DM,fields,keys,"WM",this::save).getSyncCount().get());assertEquals(7,count(dm,"T"));assertTrue(saved.get().complete());
        assertEquals(8,transfer(DM,DM,fields,keys,"WM",this::save).getSyncCount().get());assertEquals(text(dm,"S"),text(dm,"T"));
    }
    @Test void lostCommitAcknowledgementNeverAdvancesTimestampKeyCheckpoint()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"TS\" TIMESTAMP(9) NOT NULL PRIMARY KEY,\"V\" INT)");
        insert(dm,t("S"),LocalDateTime.parse("1990-04-15T02:30:00.000000001"),1);insert(dm,t("S"),LocalDateTime.parse("1990-04-15T02:30:00.000000002"),2);
        var fault=new AbstractDataSource(){public Connection getConnection()throws SQLException{Connection c=open(DM);return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{try{Object v=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("fixture lost acknowledgement");return v;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        var result=new DbSwitchTableResult();
        assertThrows(UnconfirmedWriteException.class,()->CursorTransfer.execute(runtime,ds(DM),fault,DM,DM,schema,"S",schema,"T",List.of("TS","V"),List.of("TS","V"),List.of("TS"),List.of("TS"),"TS",limits,2,binding(this::save),result,()->{}));
        assertEquals(2,count(dm,"T"));assertNull(saved.get().applied());
        transfer(DM,DM,List.of("TS","V"),List.of("TS"),"TS",this::save);assertTrue(saved.get().complete());assertEquals(2,count(dm,"T"));
        assertEquals(List.of("1990-04-15 02:30:00.000000001","1990-04-15 02:30:00.000000002"),strings(dm,"SELECT TO_CHAR(\"TS\",'YYYY-MM-DD HH24:MI:SS.FF9') FROM "+t("T")+" ORDER BY \"TS\""));
    }
    @ParameterizedTest @ValueSource(strings={"VARCHAR(8 CHAR)","CHAR(4)","TIMESTAMP(9)","DATETIME(6)","DATE","TIME(6)","DECIMAL(38,18)"})
    void frozenEmptyWindowAndUpperBoundHoldForEveryWatermarkDomain(String type)throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"W\" "+type+" NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY)");
        Object[] v=watermarks(type);var fields=List.of("W","ID");var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(DM,DM,fields,List.of("ID"),"W",next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());insert(dm,t("S"),v[1],1L);
        transfer(DM,DM,fields,List.of("ID"),"W",this::save);assertEquals(0,count(dm,"T"));
        transfer(DM,DM,fields,List.of("ID"),"W",this::save);assertEquals(1,count(dm,"T"));
        // Equal watermarks rewind with the last group (VARCHAR 'm ' equals 'm'); lower ones break the declared monotonic contract.
        insert(dm,t("S"),v[2],2L);insert(dm,t("S"),v[0],3L);insert(dm,t("S"),v[3],4L);
        transfer(DM,DM,fields,List.of("ID"),"W",this::save);
        assertEquals(List.of("1","2","4"),strings(dm,"SELECT \"ID\" FROM "+t("T")+" ORDER BY \"ID\""));
    }
    @ParameterizedTest @CsvSource(delimiter=';',value={"VARBINARY(8) NOT NULL;BIGINT NOT NULL;K;","BINARY(4) NOT NULL;BIGINT NOT NULL;K;","BIGINT NOT NULL;VARBINARY(8) NOT NULL;W;",
            "TIMESTAMP(6) WITH TIME ZONE NOT NULL;BIGINT NOT NULL;K;","BIGINT NOT NULL;TIMESTAMP(9) WITH TIME ZONE NOT NULL;W;","BIGINT NOT NULL;TIME(6) WITH TIME ZONE NOT NULL;W;",
            "BIGINT NOT NULL;BIGINT NOT NULL;W;TIMESTAMP(6) WITH TIME ZONE","NVARCHAR(8) NOT NULL;BIGINT NOT NULL;K;","DOUBLE NOT NULL;BIGINT NOT NULL;K;",
            "NUMBER NOT NULL;BIGINT NOT NULL;K;","DEC(10,2) NOT NULL;BIGINT NOT NULL;K;","BIGINT NOT NULL;VARCHAR(8 CHAR);W;"})
    void unverifiedDomainsFailBeforeReading(String key,String watermark,String order,String payload)throws Exception {
        sql(dm,"CREATE TABLE "+t("S")+"(\"K\" "+key+" PRIMARY KEY,\"W\" "+watermark+(payload==null?"":",\"X\" "+payload)+")");
        var reader=runtime.reader(ds(DM),DM);
        assertThrows(IllegalArgumentException.class,()->reader.openCursor(new CursorReadRequest(schema,"S",List.of("K","W"),order,List.of("K"),null,null,null,limits,false,pin)));
    }
    @Test void publicServiceMovesTextKeyedRowsBetweenDmSchemas()throws Exception {
        for(String table:List.of("S","T"))sql(dm,"CREATE TABLE "+t(table)+"(\"WM\" TIMESTAMP(9) NOT NULL,\"NAME\" VARCHAR(20 CHAR) NOT NULL,\"ID\" BIGINT NOT NULL,\"V\" VARCHAR(20),PRIMARY KEY(\"NAME\",\"ID\"))");
        long id=0;for(String n:List.of("中文","\uD83D\uDE00","","B")){insert(dm,t("S"),LocalDateTime.parse("1990-04-15T02:30:00.00000000"+(id%3+1)),n,id,"v"+(id+1));id++;}
        var request=request(DM,DM);var first=new MigrationService(request,ignored->{},null,runtime).run();
        assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());assertEquals(text(dm,"S"),text(dm,"T"));assertTrue(saved.get().complete());
    }
    @Test void postgresExactNumericKeysAndTemporalPayloadResumeIntoDm()throws Exception {
        String max="99999999999999999999999999999999999999";
        sql(pg,"CREATE TABLE "+t("S")+"(\"WM\" TIMESTAMP(6) NOT NULL,\"K1\" NUMERIC(38,0) NOT NULL,\"K2\" BIGINT NOT NULL,\"D\" DATE,\"N\" NUMERIC(38,18),\"TXT\" VARCHAR(20),\"P\" TIMESTAMP(6),PRIMARY KEY(\"K1\",\"K2\"))");
        sql(dm,"CREATE TABLE "+t("T")+"(\"WM\" TIMESTAMP(6) NOT NULL,\"K1\" NUMERIC(38,0) NOT NULL,\"K2\" BIGINT NOT NULL,\"D\" DATE,\"N\" NUMERIC(38,18),\"TXT\" VARCHAR(20 CHAR),\"P\" TIMESTAMP(6),PRIMARY KEY(\"K1\",\"K2\"))");
        var w1=LocalDateTime.parse("1990-04-15T02:30:00.000001");var w2=LocalDateTime.parse("2024-03-10T02:30");
        insert(pg,t("S"),w1,new BigDecimal("-"+max),Long.MIN_VALUE,LocalDate.parse("0001-01-01"),new BigDecimal("-0.000000000000000001"),"中文",w1);
        insert(pg,t("S"),w1,new BigDecimal("-9223372036854775809"),0L,LocalDate.parse("2024-02-29"),new BigDecimal("99999999999999999999.999999999999999999"),"\uD83D\uDE00",w2);
        insert(pg,t("S"),w1,new BigDecimal("9223372036854775808"),-1L,LocalDate.parse("9999-12-31"),BigDecimal.ZERO,"",LocalDateTime.parse("9999-12-31T23:59:59.999999"));
        sql(pg,"INSERT INTO "+t("S")+" VALUES(TIMESTAMP '2024-03-10 02:30:00',"+max+",9223372036854775806,NULL,NULL,'a b ',NULL)");
        insert(pg,t("S"),w2,new BigDecimal(max),Long.MAX_VALUE,LocalDate.parse("1990-04-15"),new BigDecimal("-1"),"z",LocalDateTime.parse("0001-01-01T00:00"));
        var fields=List.of("WM","K1","K2","D","N","TXT","P");var keys=List.of("K1","K2");var calls=new AtomicInteger();
        String query="SELECT \"WM\",\"K1\",\"K2\",\"D\",\"N\",\"TXT\",\"P\" FROM %s ORDER BY \"K1\",\"K2\"";Class<?>[] types={LocalDateTime.class,BigDecimal.class,BigDecimal.class,LocalDate.class,BigDecimal.class,String.class,LocalDateTime.class};
        assertThrows(IllegalStateException.class,()->transfer(PG,DM,fields,keys,"WM",next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(dm,"T"));assertEquals(1,saved.get().sequence());
        insert(pg,t("S"),w2,BigDecimal.ZERO,0L,LocalDate.parse("2000-01-01"),BigDecimal.ONE,"late equal",w1);
        // The resumed frozen window rewinds the whole first watermark group and admits the late equal-watermark row.
        assertEquals(6,transfer(PG,DM,fields,keys,"WM",this::save).getSyncCount().get());assertTrue(saved.get().complete());
        assertEquals(rows(pg,query.formatted(t("S")),types),rows(dm,query.formatted(t("T")),types));
        assertEquals(3,transfer(PG,DM,fields,keys,"WM",this::save).getSyncCount().get());assertEquals(rows(pg,query.formatted(t("S")),types),rows(dm,query.formatted(t("T")),types));
    }
    @Test void postgresTimeCarrierPreservesMillisecondsAndMicroseconds()throws Exception {
        // The PostgreSQL cursor uses its exact TIME text/LocalTime carrier, including microseconds.
        sql(pg,"CREATE TABLE "+t("S")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T3\" TIME(3))");sql(dm,"CREATE TABLE "+t("T")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T3\" TIME(3))");
        sql(pg,"INSERT INTO "+t("S")+" VALUES(1,1,TIME '12:00:00.123'),(1,2,TIME '23:59:59.999'),(1,3,NULL)");
        assertEquals(3,transfer(PG,DM,List.of("WM","ID","T3"),List.of("ID"),"WM",this::save).getSyncCount().get());
        assertEquals(List.of("1|12:00:00.123","2|23:59:59.999","3|null"),rows(dm,"SELECT \"ID\",\"T3\" FROM "+t("T")+" ORDER BY \"ID\"",BigDecimal.class,LocalTime.class));
        sql(pg,"CREATE TABLE "+t("S6")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T6\" TIME(6))");sql(dm,"CREATE TABLE "+t("T6")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T6\" TIME(6))");
        sql(pg,"INSERT INTO "+t("S6")+" VALUES(1,1,TIME '12:00:00.123456'),(1,2,TIME '23:59:59.999999'),(1,3,NULL)");saved.set(null);
        assertEquals(3,transfer(PG,DM,"S6","T6",List.of("WM","ID","T6"),List.of("ID"),"WM",this::save).getSyncCount().get());
        assertEquals(List.of("1|12:00:00.123456","2|23:59:59.999999","3|null"),rows(dm,"SELECT \"ID\",\"T6\" FROM "+t("T6")+" ORDER BY \"ID\"",BigDecimal.class,LocalTime.class));
        assertEquals(rows(pg,"SELECT \"ID\",\"T6\" FROM "+t("S6")+" ORDER BY \"ID\"",BigDecimal.class,LocalTime.class),
                rows(dm,"SELECT \"ID\",\"T6\" FROM "+t("T6")+" ORDER BY \"ID\"",BigDecimal.class,LocalTime.class));
    }
    @Test void postgresTime24BoundaryStillFailsBeforeWritingOrAdvancingCheckpoint()throws Exception {
        sql(pg,"CREATE TABLE "+t("S")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T6\" TIME(6))");
        sql(dm,"CREATE TABLE "+t("T")+"(\"WM\" BIGINT NOT NULL,\"ID\" BIGINT NOT NULL PRIMARY KEY,\"T6\" TIME(6))");
        sql(pg,"INSERT INTO "+t("S")+" VALUES(1,1,TIME '24:00:00')");
        var rejected=assertThrows(SQLException.class,()->transfer(PG,DM,List.of("WM","ID","T6"),List.of("ID"),"WM",this::save));
        assertTrue(rejected.getMessage().contains("24:00:00"));
        assertEquals(0,count(dm,"T"));assertNull(saved.get().applied());
    }
    @Test void textOrderingProfileRequiresSuffixSpaceComparisonModeZero()throws Exception {
        // SPACE_COMPARE_MODE=1 would make trailing U+0020 significant and break every proven text equality and bound.
        assertThrows(SQLException.class,()->DmCursorMetadata.requireTextOrdering(profile(Map.of("SPACE_COMPARE_MODE","1")),15));
        assertThrows(SQLException.class,()->DmCursorMetadata.requireTextOrdering(profile(Map.of("NLS_SORT_TYPE","1")),15));
        assertThrows(SQLException.class,()->DmCursorMetadata.requireTextOrdering(profile(Map.of("BLANK_PAD_MODE","1")),15));
        assertTrue(DmCursorMetadata.requireTextOrdering(profile(Map.of()),15).contains("SPACE_COMPARE_0"));
        assertTrue(DmCursorMetadata.requireTextOrdering(dm,15).contains("SPACE_COMPARE_0"));
    }

    /** Rows sorted by (TS,TS6,D,T); pages of two end on ties of TS, then TS6, then D, then T, then a one-nanosecond TS step. */
    private static List<Object[]> temporalRows() {
        String[][] r={{"0001-01-01T00:00","0001-01-01T00:00","0001-01-01","00:00"},{"1990-04-15T02:30:00.000000001","1990-04-15T02:30","1990-04-15","02:30:00.000001"},
                {"1990-04-15T02:30:00.000000001","1990-04-15T02:30:00.000001","1990-04-15","00:00"},{"2024-03-10T02:30:00.000000001","2024-03-10T02:30","2024-02-29","12:00"},
                {"2024-03-10T02:30:00.000000001","2024-03-10T02:30","2024-03-01","00:00"},{"2026-01-02T03:04:05.123456789","2026-01-02T03:04:05.123456","2026-01-02","23:59:59.999998"},
                {"2026-01-02T03:04:05.123456789","2026-01-02T03:04:05.123456","2026-01-02","23:59:59.999999"},{"9999-12-31T23:59:59.999999998","9999-12-31T23:59:59.999999","9999-12-31","23:59:59.999999"},
                {"9999-12-31T23:59:59.999999999","9999-12-31T23:59:59.999999","9999-12-31","23:59:59.999999"}};
        var rows=new ArrayList<Object[]>();for(String[] v:r)rows.add(new Object[]{LocalDateTime.parse(v[0]),LocalDateTime.parse(v[1]),LocalDate.parse(v[2]),LocalTime.parse(v[3])});return rows;
    }
    /** Lower, value, equal value and higher watermark for each verified domain. */
    private static Object[] watermarks(String type) {
        return switch(type) {
            case "VARCHAR(8 CHAR)","CHAR(4)"->new Object[]{"l","m","m ","n"};
            case "TIMESTAMP(9)"->temporal("2026-01-02T03:04:05.123456788","2026-01-02T03:04:05.123456789","2026-01-02T03:04:05.123456789","2026-01-02T03:04:05.12345679");
            case "DATETIME(6)"->temporal("1990-04-15T02:29:59.999999","1990-04-15T02:30","1990-04-15T02:30","1990-04-15T02:30:00.000001");
            case "DATE"->new Object[]{LocalDate.parse("2024-02-28"),LocalDate.parse("2024-02-29"),LocalDate.parse("2024-02-29"),LocalDate.parse("2024-03-01")};
            case "TIME(6)"->new Object[]{LocalTime.parse("12:00:00.000001"),LocalTime.parse("12:00:00.000002"),LocalTime.parse("12:00:00.000002"),LocalTime.parse("12:00:00.000003")};
            default->new Object[]{new BigDecimal("-0.000000000000000002"),new BigDecimal("-0.000000000000000001"),new BigDecimal("-0.000000000000000001"),BigDecimal.ZERO};
        };
    }
    private static Object[] temporal(String... values){return Arrays.stream(values).map(LocalDateTime::parse).toArray();}
    private List<String> temporal(Connection c,String table)throws SQLException {
        var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT \"TS\",\"TS6\",\"D\",\"T\",\"V\" FROM "+t(table)+" ORDER BY \"TS\",\"TS6\",\"D\",\"T\"")) {
            while(r.next())values.add(r.getObject(1,LocalDateTime.class)+"|"+r.getObject(2,LocalDateTime.class)+"|"+r.getObject(3,LocalDate.class)+"|"+r.getObject(4,LocalTime.class)+"|"+r.getInt(5));}
        return values;
    }
    private List<String> mixed(Connection c,String table)throws SQLException {
        var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT \"ID\",\"WM6\",\"D\",\"T\",\"NAME\" FROM "+t(table)+" ORDER BY \"ID\"")) {
            while(r.next())values.add(r.getLong(1)+"|"+r.getObject(2,LocalDateTime.class)+"|"+r.getObject(3,LocalDate.class)+"|"+r.getObject(4,LocalTime.class)+"|"+codePoints(r.getString(5)));}
        return values;
    }
    private List<String> numeric(Connection c,String table)throws SQLException {
        var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT \"WM\",\"K1\",\"K2\",\"K3\",\"V\" FROM "+t(table)+" ORDER BY \"V\"")) {
            while(r.next())values.add(plain(r.getBigDecimal(1))+"|"+plain(r.getBigDecimal(2))+"|"+r.getLong(3)+"|"+plain(r.getBigDecimal(4))+"|"+r.getString(5));}
        return values;
    }
    private List<String> text(Connection c,String table)throws SQLException {
        var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+t(table))) {
            int n=r.getMetaData().getColumnCount();while(r.next()){var row=new StringJoiner("|");for(int i=1;i<=n;i++)row.add(String.valueOf(r.getString(i)));values.add(row.toString());}}
        values.sort(null);return values;
    }
    /** Product-neutral rendering: exact decimals, java.time values and text as code points. */
    private static List<String> rows(Connection c,String query,Class<?>... types)throws SQLException {
        var values=new ArrayList<String>();
        try(var s=c.createStatement();var r=s.executeQuery(query)){while(r.next()){var row=new StringJoiner("|");for(int i=0;i<types.length;i++){
            Object v=types[i]==BigDecimal.class?r.getBigDecimal(i+1):types[i]==String.class?r.getString(i+1):r.getObject(i+1,types[i]);
            row.add(v instanceof BigDecimal n?plain(n):v instanceof String text?codePoints(text):String.valueOf(v));}values.add(row.toString());}}
        return values;
    }
    private static String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String codePoints(String text){var b=new StringJoiner(" ","[","]");text.codePoints().forEach(cp->b.add(Integer.toHexString(cp)));return b.toString();}
    private static List<String> strings(Connection c,String query)throws SQLException{var values=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery(query)){while(r.next())values.add(r.getString(1));}return values;}
    /** Typed binds: the fixture values are the exact java.time values the assertions compare. */
    private static void insert(Connection c,String table,Object... row)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO "+table+" VALUES("+String.join(",",Collections.nCopies(row.length,"?"))+")")) {
            for(int i=0;i<row.length;i++){Object v=row[i];if(v instanceof LocalDateTime)p.setObject(i+1,v,Types.TIMESTAMP);else if(v instanceof LocalDate)p.setObject(i+1,v,Types.DATE);else if(v instanceof LocalTime)p.setObject(i+1,v,Types.TIME);else p.setObject(i+1,v);}
            p.setQueryTimeout(15);p.executeUpdate();
        }
    }
    private static Connection profile(Map<String,String> overrides) {
        var verified=new LinkedHashMap<String,String>();verified.put("SF_GET_UNICODE_FLAG","1");verified.put("SF_GET_CASE_SENSITIVE_FLAG","1");verified.put("BLANK_PAD_MODE","0");
        verified.put("SPACE_COMPARE_MODE","0");verified.put("NLS_SORT_TYPE","0");verified.put("COMPATIBLE_MODE","0");
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(!m.getName().equals("prepareStatement"))return null;String sql=(String)a[0];
            // Answer each selected profile item by name, in the order the statement selects them.
            var answer=verified.keySet().stream().filter(sql::contains).sorted(Comparator.comparingInt(sql::indexOf)).map(k->overrides.getOrDefault(k,verified.get(k))).toList();
            var next=new AtomicBoolean(true);
            var rows=(ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->switch(rm.getName()){
                case "next"->next.getAndSet(false);case "getString"->answer.get((Integer)ra[0]-1);case "wasNull"->false;default->null;});
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->sm.getName().equals("executeQuery")?rows:null);
        });
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source,ProductTypeEnum target,List<String> fields,List<String> keys,String watermark,CursorRunBinding.CheckpointStore store)throws Exception {
        return transfer(source,target,"S","T",fields,keys,watermark,store);
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source,ProductTypeEnum target,String sourceTable,String targetTable,List<String> fields,List<String> keys,String watermark,CursorRunBinding.CheckpointStore store)throws Exception {
        var result=new DbSwitchTableResult();CursorTransfer.execute(runtime,ds(source),ds(target),source,target,schema,sourceTable,schema,targetTable,fields,fields,keys,keys,watermark,limits,2,binding(store),result,()->{});return result;
    }
    private MigrationRequest request(ProductTypeEnum source,ProductTypeEnum target) {
        var r=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(source);s.setUrl(url(source));s.setUsername(user(source));s.setPassword(password(source));s.setPoolSize(1);s.setSourceSchema(schema);s.setSourceIncludes("S");s.setFetchSize(2);s.setIncreaseColumnName("WM");s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");s.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));r.getSource().add(s);
        var t=r.getTarget();t.setTargetProductType(target);t.setUrl(url(target));t.setUsername(user(target));t.setPassword(password(target));t.setTargetSchema(schema);t.setPoolSize(1);t.setChangeDataSync(true);t.setChangeDataSyncType("INCREASE_COLUMN");t.setCursorRunBindings(Map.of("S",binding(this::save)));return r;
    }
    private Connection open(ProductTypeEnum p)throws SQLException{return runtime.openConnection(p,null,url(p),user(p),password(p));}
    private DataSource ds(ProductTypeEnum p){return new AbstractDataSource(){public Connection getConnection()throws SQLException{return open(p);}public Connection getConnection(String u,String pw)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection control(ProductTypeEnum p){return p==DM?dm:pg;}
    private long count(Connection c,String table)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t(table))){assertTrue(r.next());return r.getLong(1);}}
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store){return new CursorRunBinding("d".repeat(64),saved.get(),store,pin);}
    private boolean save(CursorCheckpoint c){saved.set(c);return true;}
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String n){return DmSqlNames.quote(n);}private static void sql(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private static String url(ProductTypeEnum p){return System.getProperty(p==DM?"datahub.test.dm.url":"datahub.test.postgres.url");}
    private static String user(ProductTypeEnum p){return p==DM?"SYSDBA":System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(ProductTypeEnum p){return p==DM?System.getenv("DATAHUB_DM_TEST_PASSWORD"):System.getProperty("datahub.test.postgres.password","");}
}
