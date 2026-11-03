package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SqliteNativeMigrationTest {
    @TempDir Path directory;
    ConfiguredJdbcDrivers drivers;DbSwitchRuntime runtime;HikariDataSource source,target;String sourceUrl,targetUrl;
    @BeforeEach void setup()throws Exception {
        String supplied=System.getProperty("datahub.test.sqlite.driver");
        if(supplied!=null){Path artifact=Path.of(supplied);var spec=new DbSwitchProperties.Driver();spec.setPath(artifact.toAbsolutePath().toString());
            spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));spec.setDriverClassName("org.sqlite.JDBC");
            drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.SQLITE3,spec));
        }else{Class.forName("org.sqlite.JDBC");drivers=new ConfiguredJdbcDrivers(Map.of());}
        runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        sourceUrl="jdbc:sqlite:"+directory.resolve("source.db");targetUrl="jdbc:sqlite:"+directory.resolve("target.db");
        source=pool(sourceUrl);target=pool(targetUrl);
    }
    @AfterEach void cleanup(){try{if(source!=null)source.close();}finally{try{if(target!=null)target.close();}finally{if(runtime!=null)runtime.close();}}}
    HikariDataSource pool(String url){var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.SQLITE3);properties.setUrl(url);properties.setPoolSize(1);
        return DataSourceUtils.createTargetDataSource(properties,null,null,drivers.find(ProductTypeEnum.SQLITE3).orElse(null),drivers.runtimes());}
    @Test void publicFullPreservesCheckUniqueGeneratedPartialExpressionIndexesAndLiteralNames()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY, note TEXT NOT NULL COLLATE NOCASE CHECK(note <> 'note; source_rows'), amount INTEGER DEFAULT(2+3), doubled INTEGER GENERATED ALWAYS AS(amount*2) STORED, UNIQUE(note))");
        sql(source,"CREATE INDEX source_partial ON source_rows(lower(note) DESC, amount ASC) WHERE amount>0 AND note<>'note'");
        sql(source,"INSERT INTO source_rows(id,note,amount) VALUES(9007199254740993,'alpha',7),(2,'beta',NULL)");
        var result=run(false,false,false,Map.of("note","description","amount","value"));
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals(List.of("2|beta|null|null","9007199254740993|alpha|7|14"),rows(target,"SELECT id,description,value,doubled FROM target_rows ORDER BY id"));
        assertThrows(SQLException.class,()->sql(target,"INSERT INTO target_rows(id,description) VALUES(3,'ALPHA')"));
        assertThrows(SQLException.class,()->sql(target,"INSERT INTO target_rows(id,description) VALUES(3,'note; source_rows')"));
        String index=scalar(target,"SELECT sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL");
        assertTrue(index.contains("description"));assertTrue(index.contains("'note'"));assertTrue(index.contains("DESC"));
        sql(target,"INSERT INTO target_rows(id,description) VALUES(3,'defaulted')");assertEquals("5|10",rows(target,"SELECT value,doubled FROM target_rows WHERE id=3").getFirst());
    }
    @Test void withoutRowidAndSelfReferenceSurviveTableAndColumnRenaming()throws Exception {
        sql(source,"CREATE TABLE source_rows(id TEXT PRIMARY KEY,parent TEXT REFERENCES source_rows(id) ON DELETE RESTRICT,note TEXT CHECK(length(note)>0)) WITHOUT ROWID");
        sql(source,"INSERT INTO source_rows VALUES('a',NULL,'root'),('b','a','child')");
        var result=run(false,false,false,Map.of("id","key","parent","parent_key"));assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertTrue(scalar(target,"SELECT sql FROM sqlite_master WHERE name='target_rows'").endsWith("WITHOUT ROWID"));
        assertEquals("target_rows|parent_key|key",rows(target,"SELECT \"table\",\"from\",\"to\" FROM pragma_foreign_key_list('target_rows')").getFirst());
        var writer=runtime.writer(target,ProductTypeEnum.SQLITE3,cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite("main","target_rows",List.of("key","parent_key","note"));
        assertThrows(RuntimeException.class,()->writer.write(List.of("key","parent_key","note"),Collections.singletonList(new Object[]{"c","absent","invalid"})));
        assertEquals("0",scalar(target,"SELECT count(*) FROM target_rows WHERE \"key\"='c'"));
    }
    @Test void differenceIgnoresGeneratedInputAndRecomputesItsExpression()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,n INTEGER,g INTEGER GENERATED ALWAYS AS(n*3) VIRTUAL)");
        sql(source,"INSERT INTO source_rows(id,n) VALUES(1,2),(2,4)");assertTrue(run(false,false,false,Map.of()).getIfAllSuccess().get());
        sql(source,"UPDATE source_rows SET n=7 WHERE id=1");sql(source,"DELETE FROM source_rows WHERE id=2");sql(source,"INSERT INTO source_rows(id,n) VALUES(3,9)");
        var diff=run(true,false,false,Map.of());assertTrue(diff.getIfAllSuccess().get(),()->diff.getTableResultList().toString());
        assertEquals(List.of("1|7|21","3|9|27"),rows(target,"SELECT id,n,g FROM target_rows ORDER BY id"));
    }
    @Test void autoincrementRetainsDeletedHighWaterForDataButNotStructureOnly()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY AUTOINCREMENT,note TEXT)");
        sql(source,"INSERT INTO source_rows VALUES(1000,'deleted'),(1,'retained')");sql(source,"DELETE FROM source_rows WHERE id=1000");
        assertTrue(run(false,false,false,Map.of()).getIfAllSuccess().get());sql(target,"INSERT INTO target_rows(note) VALUES('next')");assertEquals("1001",scalar(target,"SELECT max(id) FROM target_rows"));
        sql(target,"DROP TABLE target_rows");assertTrue(run(false,false,true,Map.of()).getIfAllSuccess().get());sql(target,"INSERT INTO target_rows(note) VALUES('new')");assertEquals("1",scalar(target,"SELECT id FROM target_rows"));
    }
    @Test @EnabledIfSystemProperty(named="datahub.test.sqlite.driver",matches=".+")
    void verifiedModernDriverPreservesStrictAnyStorageAndRejectsWrongType()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY, exact ANY, n INTEGER) STRICT");
        sql(source,"INSERT INTO source_rows VALUES(1,'000123',8)");var result=run(false,false,false,Map.of());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals("000123|text",rows(target,"SELECT exact,typeof(exact) FROM target_rows").getFirst());assertTrue(scalar(target,"SELECT sql FROM sqlite_master WHERE name='target_rows'").endsWith("STRICT"));
        assertThrows(SQLException.class,()->sql(target,"INSERT INTO target_rows VALUES(2,'x','not_integer')"));
    }
    @Test void explicitReplacementKeepsOldRowsWhenPreflightDetectsTriggerOrExternalForeignKey()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(source,"CREATE TRIGGER source_trigger AFTER INSERT ON source_rows BEGIN UPDATE source_rows SET note='triggered' WHERE id=new.id; END");
        sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'keep')");
        assertThrows(RuntimeException.class,()->runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows"));assertEquals("keep",scalar(target,"SELECT note FROM target_rows"));
        sql(source,"DROP TRIGGER source_trigger");sql(source,"CREATE TABLE external_parent(id INTEGER PRIMARY KEY)");sql(source,"DROP TABLE source_rows");sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES external_parent(id))");
        assertThrows(RuntimeException.class,()->runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows"));assertEquals("keep",scalar(target,"SELECT note FROM target_rows"));
    }
    @Test void ddlPlanRejectsSourceTargetAliasAndCatalogChangeBeforeDrop()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'keep')");
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        assertThrows(RuntimeException.class,()->runtime.reader(source,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","source_rows",Map.of("id","id","note","note"),true));
        var plan=runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true);
        sql(target,"CREATE INDEX later_index ON target_rows(note)");assertThrows(RuntimeException.class,plan::execute);assertEquals("keep",scalar(target,"SELECT note FROM target_rows"));
    }
    @Test void incomingForeignKeyAndViewCannotBeSilentlyBrokenByReplacement()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'keep')");
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        sql(target,"CREATE TABLE child(id INTEGER REFERENCES target_rows(id))");
        assertThrows(RuntimeException.class,()->runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true));
        sql(target,"DROP TABLE child");sql(target,"CREATE VIEW dependent AS SELECT note FROM target_rows");
        assertThrows(RuntimeException.class,()->runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true));assertEquals("keep",scalar(target,"SELECT note FROM target_rows"));
    }
    @Test void mappedColumnCycleUsesNativeAstAndLeavesLiteralValuesUntouched()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,a TEXT CHECK(a<>'a'),b TEXT CHECK(b<>'b'))");sql(source,"INSERT INTO source_rows VALUES(1,'left','right')");
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        var plan=runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","a","b","b","a"),false);plan.execute();
        assertThrows(SQLException.class,()->sql(target,"INSERT INTO target_rows(id,b,a) VALUES(2,'a','ok')"));assertThrows(SQLException.class,()->sql(target,"INSERT INTO target_rows(id,b,a) VALUES(2,'ok','b')"));
        assertTrue(rows(target,"PRAGMA database_list").stream().noneMatch(row->row.contains("dbsw_compile_")));
    }
    @Test void createIndexFailureAfterDropRollsBackBothSchemaAndOldRows()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(source,"CREATE INDEX source_index ON source_rows(note)");
        sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'preserved')");
        var active=new java.util.concurrent.atomic.AtomicBoolean();
        DataSource failing=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {
                Connection delegate=target.getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{Object result=method.invoke(delegate,args);
                        if(result instanceof Statement statement&&method.getName().equals("createStatement"))return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(sp,sm,sa)->{
                            if(active.get()&&sm.getName().equals("execute")&&sa!=null&&sa[0] instanceof String text&&text.startsWith("CREATE INDEX"))throw new SQLException("fixture index creation failure");
                            try{return sm.invoke(statement,sa);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
            @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        };
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        var plan=runtime.reader(failing,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true);
        active.set(true);assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,plan::execute);
        assertEquals("preserved",scalar(target,"SELECT note FROM target_rows WHERE id=9"));assertEquals("0",scalar(target,"SELECT count(*) FROM sqlite_master WHERE type='index' AND tbl_name='target_rows'"));
    }
    @Test void deterministicIndexNameCollisionFailsBeforeReplacement()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(source,"CREATE INDEX original_index ON source_rows(note)");
        sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'preserved')");sql(target,"CREATE TABLE unrelated(v TEXT)");
        String occupied="dbsw_ix_"+cn.hutool.crypto.digest.DigestUtil.sha256Hex("target_rows\0original_index").substring(0,32);sql(target,"CREATE INDEX \""+occupied+"\" ON unrelated(v)");
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        assertThrows(RuntimeException.class,()->runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true));
        assertEquals("preserved",scalar(target,"SELECT note FROM target_rows"));
    }
    @Test void hardLinkAliasCannotAuthorizeDroppingItsOwnSourceTable()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(source,"INSERT INTO source_rows VALUES(1,'source')");
        Path alias=Files.createLink(directory.resolve("source-alias.db"),directory.resolve("source.db"));
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        try(var aliased=pool("jdbc:sqlite:"+alias)){
            assertThrows(RuntimeException.class,()->runtime.reader(aliased,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","source_rows",Map.of("id","id","note","note"),true));
        }
        assertEquals("source",scalar(source,"SELECT note FROM source_rows"));
    }
    @Test void sameNameAndSameDdlAfterRebuildDoNotSatisfyAnOlderPlan()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");
        var definition=runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows");
        var plan=runtime.reader(target,ProductTypeEnum.SQLITE3).prepareNativeTarget(definition,"main","target_rows",Map.of("id","id","note","note"),true);
        sql(target,"DROP TABLE target_rows");sql(target,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY,note TEXT)");sql(target,"INSERT INTO target_rows VALUES(9,'new-owner')");
        assertThrows(RuntimeException.class,plan::execute);assertEquals("new-owner",scalar(target,"SELECT note FROM target_rows"));
    }
    @Test @EnabledIfSystemProperty(named="datahub.test.sqlite.driver",matches=".+")
    void modernSharedCatalogKeepsStrictAndGeneratedFactsWithoutClaimingAnExactPortableDomain()throws Exception {
        sql(source,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY, n INTEGER, twice INTEGER GENERATED ALWAYS AS(n*2) STORED) STRICT");
        try(var connection=source.getConnection()) {
            try(var statement=connection.createStatement()){statement.execute("PRAGMA busy_timeout=2222");}
            var metadata=cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                    connection,ProductTypeEnum.SQLITE3,"main","source_rows",10);
            assertEquals("3.49.1",metadata.provenance().serverVersion());
            assertTrue(metadata.provenance().nativeProduct(ProductTypeEnum.SQLITE3));
            assertEquals("SQLITE_CATALOG_V1",metadata.provenance().method());
            assertEquals(List.of("id","n","twice"),metadata.columns().stream().map(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription::getFieldName).toList());
            assertEquals(List.of("id"),metadata.primaryKeys());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.UNKNOWN,metadata.additionalObjects().state());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.UNKNOWN,metadata.columns().get(2).getMetadataEvidence().generatedExpression().state());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.LOSSY,
                    new Sqlite3LogicalTypeMapper().toLogical(metadata.columns().getFirst()).fidelity());
            try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA busy_timeout")){
                assertTrue(rows.next());assertEquals(2222,rows.getInt(1));
            }
        }
    }
    @Test @EnabledIfSystemProperty(named="datahub.test.sqlite.driver",matches=".+")
    void strictWithoutRowidOptionsRemainNativeCatalogProperties()throws Exception {
        sql(source,"CREATE TABLE source_rows(id TEXT PRIMARY KEY,n INTEGER) STRICT, WITHOUT ROWID");sql(source,"INSERT INTO source_rows VALUES('a',2)");
        var result=run(false,false,false,Map.of());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        assertEquals("1|1",rows(target,"SELECT wr,strict FROM pragma_table_list WHERE name='target_rows'").getFirst());
    }
    @Test void virtualTableIsRejectedWithoutPretendingItIsAnOrdinaryTable()throws Exception {
        sql(source,"CREATE VIRTUAL TABLE source_rows USING fts5(body)");
        assertThrows(RuntimeException.class,()->runtime.reader(source,ProductTypeEnum.SQLITE3).describeNativeTable("main","source_rows"));
        assertEquals("0",scalar(target,"SELECT count(*) FROM sqlite_master WHERE name='target_rows'"));
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult run(boolean diff,boolean replace,boolean only,Map<String,String> columns)throws Exception {
        var request=new MigrationRequest();var s=new SourceDataSourceProperties();s.setSourceProductType(ProductTypeEnum.SQLITE3);s.setUrl(sourceUrl);s.setSourceSchema("main");s.setSourceIncludes("source_rows");s.setPoolSize(1);
        s.setRegexTableMapper(List.of(new PatternMapper("^source_rows$","target_rows")));
        s.setRegexColumnMapper(columns.entrySet().stream().map(e->new PatternMapper("^"+java.util.regex.Pattern.quote(e.getKey())+"$",e.getValue())).toList());request.getSource().add(s);
        var t=request.getTarget();t.setTargetProductType(ProductTypeEnum.SQLITE3);t.setUrl(targetUrl);t.setTargetSchema("main");t.setPoolSize(1);t.setWriterEngineInsert(true);t.setTargetDrop(replace);t.setOnlyCreate(only);t.setChangeDataSync(diff);if(diff)t.setChangeDataSyncType("PK_ORDER_COMPARE");
        return new MigrationService(request,ignored->{},null,runtime).run();
    }
    private static void sql(DataSource ds,String text)throws Exception{try(var c=ds.getConnection();var s=c.createStatement()){s.execute(text);}}
    private static String scalar(DataSource ds,String text)throws Exception{return rows(ds,text).getFirst();}
    private static List<String> rows(DataSource ds,String text)throws Exception{var output=new ArrayList<String>();try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){while(r.next()){var values=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)values.add(String.valueOf(r.getObject(i)));output.add(String.join("|",values));}}return output;}
}
