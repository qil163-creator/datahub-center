package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqliteDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.SqliteUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3ProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SqliteCursorTransferTest {
    @TempDir Path directory;
    private static final String TABLE="line \"item";
    private static final List<String> FIELDS=List.of("wm","k1","k2","payload","raw"),KEYS=List.of("k1","k2");
    private static final BatchLimits LIMITS=new BatchLimits(2,16384,4096,100,1000);
    private static final String BINDING="b".repeat(64);
    private DataSource source,target;
    private SourceGenerationPin pin;
    private final AtomicReference<CursorCheckpoint> checkpoint=new AtomicReference<>();
    @BeforeEach void setup()throws Exception {
        Class.forName("org.sqlite.JDBC");source=ds(directory.resolve("source.db"));target=ds(directory.resolve("target.db"));
        create(source,"main",TABLE);create(target,"main",TABLE);
        try(var c=source.getConnection()){pin=SqliteSourceGeneration.initialize(c,"main");}
    }
    @Test void repeatedWatermarkCompositeKeysPageInNativeOrderAndPreserveSigned64AndExactText()throws Exception {
        seed();transfer(target,binding(checkpoint::set));assertContents();assertTrue(checkpoint.get().complete());
        assertEquals(ProductTypeEnum.SQLITE3,checkpoint.get().sourceProduct());assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,checkpoint.get().lastCommitAcknowledgement());
        transfer(target,binding(checkpoint::set));assertContents();
        sql(source,"UPDATE "+ref("main",TABLE)+" SET payload='late equal watermark' WHERE k2='a'");
        transfer(target,binding(checkpoint::set));assertEquals("late equal watermark",scalar(target,"SELECT payload FROM "+ref("main",TABLE)+" WHERE k2='a'"));
    }
    @Test void emptyWindowCanCompleteThenReadRowsWithoutInventingAnInitialCursor()throws Exception {
        transfer(target,binding(checkpoint::set));assertTrue(checkpoint.get().complete());assertNull(checkpoint.get().fixedUpper());assertNull(checkpoint.get().applied());
        seed();transfer(target,binding(checkpoint::set));assertContents();
    }
    @Test void lostTargetCommitReplyNeverAdvancesAppliedCursorAndReplaysIdempotently()throws Exception {
        seed();var armed=new AtomicBoolean(true);var broken=lostCommit(target,armed);
        assertThrows(UnconfirmedWriteException.class,()->transfer(broken,binding(checkpoint::set)));
        assertFalse(armed.get());assertNotNull(checkpoint.get());assertNull(checkpoint.get().applied());assertFalse(checkpoint.get().complete());
        transfer(target,binding(checkpoint::set));assertContents();
    }
    @Test void lostCheckpointReplyAfterConfirmedTargetCommitRewindsWithoutDuplicateRows()throws Exception {
        seed();var armed=new AtomicBoolean(true);
        var binding=new CursorRunBinding(BINDING,null,next->{checkpoint.set(next);if(next.sequence()==1&&armed.compareAndSet(true,false))throw new IllegalStateException("checkpoint response lost");return true;},pin);
        assertThrows(IllegalStateException.class,()->transfer(target,binding));assertEquals(1,checkpoint.get().sequence());
        transfer(target,binding(checkpoint::set));assertContents();
    }
    @Test void refusedCheckpointCasAfterCommitLeavesOnlyOldAppliedPosition()throws Exception {
        seed();var binding=new CursorRunBinding(BINDING,null,next->{if(next.sequence()>0)return false;checkpoint.set(next);return true;},pin);
        assertThrows(IllegalStateException.class,()->transfer(target,binding));assertEquals(0,checkpoint.get().sequence());
        transfer(target,binding(checkpoint::set));assertContents();
    }
    @Test void missingChangedAndRotatedSourceMarkersRejectTheOldCheckpoint()throws Exception {
        seed();transfer(target,binding(checkpoint::set));
        try(var c=source.getConnection()){
            assertEquals(pin,SqliteSourceGeneration.initialize(c,"main"));
            var next=SqliteSourceGeneration.rotate(c,pin);assertNotEquals(pin,next);
        }
        assertThrows(IllegalArgumentException.class,()->transfer(target,binding(checkpoint::set)));
        sql(source,"DROP TABLE main."+SqliteCursorMetadata.quote(SqliteSourceGeneration.TABLE));
        assertThrows(IllegalArgumentException.class,()->reader(request(null,null,null,false)));
    }
    @Test void replacementDatabaseAtSamePathCannotReuseAnExistingMarkerOrCheckpoint()throws Exception {
        seed();transfer(target,binding(checkpoint::set));
        Path file=directory.resolve("source.db"),replacement=directory.resolve("replacement.db");var replacementSource=ds(replacement);
        create(replacementSource,"main",TABLE);SourceGenerationPin replacementPin;
        try(var c=replacementSource.getConnection()){replacementPin=SqliteSourceGeneration.initialize(c,"main");}
        Files.move(file,directory.resolve("retired.db"));Files.move(replacement,file);
        assertThrows(IllegalArgumentException.class,()->transfer(target,binding(checkpoint::set)));
        var newer=new CursorRunBinding(BINDING,checkpoint.get(),next->{throw new AssertionError("No checkpoint before generation verification");},replacementPin);
        assertThrows(IllegalArgumentException.class,()->transfer(target,newer));
    }
    @Test void integerAffinityWithMixedStorageOrNullWatermarkIsRejectedBeforeTargetWrite()throws Exception {
        sql(source,"INSERT INTO "+ref("main",TABLE)+"(wm,k1,k2) VALUES('not-integer',1,'key')");
        assertThrows(IllegalArgumentException.class,()->transfer(target,binding(checkpoint::set)));assertEquals("0",scalar(target,"SELECT COUNT(*) FROM "+ref("main",TABLE)));
        sql(source,"DELETE FROM "+ref("main",TABLE));
        sql(source,"INSERT INTO "+ref("main",TABLE)+"(wm,k1,k2) VALUES(1,1.5,'key')");
        assertThrows(IllegalArgumentException.class,()->transfer(target,binding(checkpoint::set)));
    }
    @Test void implicitRowidNullablePrimaryKeyAndNoCaseContractsAreNotMisrepresented()throws Exception {
        for(String ddl:List.of("CREATE TABLE bad(wm INTEGER NOT NULL,payload TEXT)",
                "CREATE TABLE bad(wm INTEGER NOT NULL,k TEXT PRIMARY KEY)",
                "CREATE TABLE bad(wm INTEGER NOT NULL,k TEXT NOT NULL PRIMARY KEY COLLATE NOCASE)")){
            sql(source,ddl);var keys=ddl.contains("PRIMARY")?List.of("k"):List.of("rowid");
            var request=new CursorReadRequest("main","bad",List.of("wm",keys.getFirst()),"wm",keys,null,null,null,LIMITS,false,pin);
            assertThrows(IllegalArgumentException.class,()->reader(request));sql(source,"DROP TABLE bad");
        }
    }
    @Test void explicitIntegerPrimaryKeyAliasIsStableAndNeverGeneratedFromNullInput()throws Exception {
        sql(target,"CREATE TABLE alias_key(id INTEGER PRIMARY KEY,payload TEXT)");
        var writer=new SqliteUpsertWriter(target);writer.prepareUpsert("main","alias_key",List.of("id","payload"),List.of("id"));
        assertEquals(1,writer.write(List.of("id","payload"),List.<Object[]>of(new Object[]{Long.MAX_VALUE,"one"})));
        assertEquals(1,writer.write(List.of("id","payload"),List.<Object[]>of(new Object[]{Long.MAX_VALUE,"two"})));
        assertEquals(Long.toString(Long.MAX_VALUE),scalar(target,"SELECT id FROM alias_key"));assertEquals("two",scalar(target,"SELECT payload FROM alias_key"));
        assertThrows(IllegalArgumentException.class,()->writer.write(List.of("id","payload"),List.<Object[]>of(new Object[]{null,"null key"})));
    }
    @Test void upsertDoesNotUseReplaceAndRejectsTriggersOrLossyNumericAffinity()throws Exception {
        var writer=new SqliteUpsertWriter(target);writer.configureBatchLimits(LIMITS);writer.prepareUpsert("main",TABLE,FIELDS,KEYS);
        assertThrows(IllegalArgumentException.class,()->writer.write(FIELDS,List.<Object[]>of(new Object[]{1L,new java.math.BigInteger("9223372036854775808"),"bad",null,null})));
        assertEquals("0",scalar(target,"SELECT COUNT(*) FROM "+ref("main",TABLE)));
        sql(target,"CREATE TRIGGER hidden_effect AFTER UPDATE ON "+SqliteCursorMetadata.quote(TABLE)+" BEGIN SELECT 1; END");
        assertThrows(IllegalArgumentException.class,()->new SqliteUpsertWriter(target).prepareUpsert("main",TABLE,FIELDS,KEYS));
    }
    @Test void malformedTextCannotAdvanceTheCursorThroughJdbcReplacementCharacters()throws Exception {
        sql(source,"INSERT INTO "+ref("main",TABLE)+"(wm,k1,k2,payload) VALUES(1,1,'key',CAST(x'80' AS TEXT))");
        assertThrows(SQLException.class,()->transfer(target,binding(checkpoint::set)));assertNull(checkpoint.get().applied());
        assertEquals("0",scalar(target,"SELECT COUNT(*) FROM "+ref("main",TABLE)));
    }
    @Test void frozenEmptyWindowDoesNotAbsorbLaterInsertsWhileNextRunCanSeeThem()throws Exception {
        var first=reader(request(null,null,null,false));assertNull(first.upperBound());String fingerprint=first.schemaFingerprint();first.close();
        seed();try(var frozen=reader(request(null,null,fingerprint,true))){assertFalse(frozen.next());}
        try(var newWindow=reader(request(null,null,fingerprint,false))){assertTrue(newWindow.next());}
    }
    @Test void attachedFileSchemaAndQuotedTableResolveTheActualFile()throws Exception {
        Path attached=directory.resolve("attached.db");DataSource direct=ds(attached);create(direct,"main",TABLE);
        var attachedSource=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{var c=source.getConnection();try(var s=c.prepareStatement("ATTACH DATABASE ? AS attached")){s.setString(1,attached.toString());s.execute();}return c;}
            @Override public Connection getConnection(String u,String p)throws SQLException{return getConnection();}
        };
        SourceGenerationPin attachedPin;try(var c=attachedSource.getConnection()){attachedPin=SqliteSourceGeneration.initialize(c,"attached");}
        sql(attachedSource,"INSERT INTO "+ref("attached",TABLE)+"(wm,k1,k2,payload) VALUES(9007199254740993,9223372036854775807,'key','attached')");
        var reader=new SqliteDatabaseOperator(attachedSource);var request=new CursorReadRequest("attached",TABLE,FIELDS,"wm",KEYS,null,null,null,LIMITS,false,attachedPin);
        try(var cursor=reader.openCursor(request)){assertTrue(cursor.next());assertEquals(Long.MAX_VALUE,cursor.row()[1]);assertEquals("attached",cursor.row()[3]);assertFalse(cursor.next());}
    }
    @Test void publicMigrationServiceUsesPinnedSqliteCursorAndNativeUpsertAndResumes()throws Exception {
        seed();var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.SQLITE3);input.setDriverClassName("org.sqlite.JDBC");input.setUrl("jdbc:sqlite:"+directory.resolve("source.db"));
        input.setUsername("");input.setPassword("");input.setSourceSchema("main");input.setSourceIncludes(TABLE);input.setPoolSize(1);input.setFetchSize(1);
        input.setIncreaseColumnName("wm");input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.SQLITE3);output.setDriverClassName("org.sqlite.JDBC");output.setUrl("jdbc:sqlite:"+directory.resolve("target.db"));
        output.setUsername("");output.setPassword("");output.setTargetSchema("main");output.setPoolSize(1);output.setChangeDataSync(true);output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setBatchLimits(LIMITS);output.setCursorRunBindings(Map.of(TABLE,binding(checkpoint::set)));
        try(var runtime=new DbSwitchRuntime(new DbSwitchProductRegistry(new Sqlite3ProductProvider().products()),DbSwitchOptions.defaults(),null)){
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertContents();assertTrue(checkpoint.get().complete());
            output.setCursorRunBindings(Map.of(TABLE,binding(checkpoint::set)));
            assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());assertContents();
        }
    }
    @Test void walSnapshotExcludesConcurrentRowsAndFollowingWindowRewindsTheirWatermarkGroup()throws Exception {
        sql(source,"PRAGMA journal_mode=WAL");seed();
        try(var cursor=reader(request(null,null,null,false))){
            sql(source,"INSERT INTO "+ref("main",TABLE)+"(wm,k1,k2,payload) VALUES(9007199254740993,9223372036854775807,'late','later commit')");
            int count=0;while(cursor.next())count++;assertEquals(3,count,"All keyset pages must stay in the same SQLite snapshot");
        }
        try(var cursor=reader(request(null,null,null,false))){int count=0;while(cursor.next())count++;assertEquals(4,count);}
    }
    @Test void generationMarkerCannotBeReplacedByAViewReturningTheExpectedStrings()throws Exception {
        sql(source,"DROP TABLE "+SqliteSourceGeneration.TABLE);
        sql(source,"CREATE VIEW "+SqliteSourceGeneration.TABLE+" AS SELECT 1 AS slot,'"+pin.instanceId()+"' AS instance_id,'"+pin.generation()+"' AS generation");
        assertThrows(IllegalArgumentException.class,()->reader(request(null,null,null,false)));
    }
    @Test void unsupportedOrderingIsRefusedBeforeAnyCheckpointOrTargetWrite()throws Exception {
        sql(source,"CREATE TABLE fractional(wm NUMERIC NOT NULL,id INTEGER PRIMARY KEY)");
        sql(source,"INSERT INTO fractional VALUES(1.25,1)");
        var request=new CursorReadRequest("main","fractional",List.of("wm","id"),"wm",List.of("id"),null,null,null,LIMITS,false,pin);
        assertThrows(IllegalArgumentException.class,()->reader(request));assertNull(checkpoint.get());
    }
    private void seed()throws Exception {
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO "+ref("main",TABLE)+" VALUES(?,?,?,?,?)")){
            for(String key:List.of("a","😀","𐀀")){s.setLong(1,9007199254740993L);s.setLong(2,Long.MAX_VALUE);s.setString(3,key);s.setString(4,"精确12345678901234567890.123456789'\\\n");s.setBytes(5,new byte[]{0,-1,2});s.executeUpdate();}
        }
    }
    private void assertContents()throws Exception {
        try(var c=target.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+ref("main",TABLE)+" ORDER BY k2 COLLATE BINARY")){
            for(String key:List.of("a","𐀀","😀")){assertTrue(r.next());assertEquals(9007199254740993L,r.getLong(1));assertEquals(Long.MAX_VALUE,r.getLong(2));assertEquals(key,r.getString(3));assertEquals("精确12345678901234567890.123456789'\\\n",r.getString(4));assertArrayEquals(new byte[]{0,-1,2},r.getBytes(5));}assertFalse(r.next());
        }
    }
    private CursorRunBinding binding(java.util.function.Consumer<CursorCheckpoint> sink){return new CursorRunBinding(BINDING,checkpoint.get(),next->{sink.accept(next);return true;},pin);}
    private void transfer(DataSource destination,CursorRunBinding binding)throws Exception{
        try(var runtime=new DbSwitchRuntime(new DbSwitchProductRegistry(new Sqlite3ProductProvider().products()),DbSwitchOptions.defaults(),null)){
            CursorTransfer.execute(runtime,source,destination,ProductTypeEnum.SQLITE3,ProductTypeEnum.SQLITE3,"main",TABLE,"main",TABLE,FIELDS,FIELDS,KEYS,KEYS,"wm",LIMITS,1,binding,new DbSwitchTableResult(),()->{});
        }
    }
    private CursorReadRequest request(cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor lower,cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor upper,String fingerprint,boolean frozen){return new CursorReadRequest("main",TABLE,FIELDS,"wm",KEYS,lower,upper,fingerprint,LIMITS,frozen,pin);}
    private CursorReadSession reader(CursorReadRequest request){var operator=new SqliteDatabaseOperator(source);operator.setFetchSize(1);return operator.openCursor(request);}
    private static DataSource ds(Path file){return new DriverManagerDataSource("jdbc:sqlite:"+file.toAbsolutePath());}
    private static void create(DataSource ds,String schema,String table)throws Exception{sql(ds,"CREATE TABLE "+ref(schema,table)+"(wm INTEGER NOT NULL,k1 INTEGER NOT NULL,k2 TEXT COLLATE BINARY NOT NULL,payload TEXT,raw BLOB,PRIMARY KEY(k1,k2)) WITHOUT ROWID");}
    private static String ref(String schema,String table){return SqliteCursorMetadata.reference(schema,table);}
    private static void sql(DataSource ds,String sql)throws Exception{try(var c=ds.getConnection();var s=c.createStatement()){s.execute(sql);}}
    private static String scalar(DataSource ds,String sql)throws Exception{try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private static DataSource lostCommit(DataSource source,AtomicBoolean armed){return new AbstractDataSource(){
        @Override public Connection getConnection()throws SQLException{var actual=source.getConnection();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{Object result=m.invoke(actual,a);if(m.getName().equals("commit")&&armed.compareAndSet(true,false))throw new SQLException("fixture commit reply lost");return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });}
        @Override public Connection getConnection(String u,String p)throws SQLException{return getConnection();}
    };}
}
