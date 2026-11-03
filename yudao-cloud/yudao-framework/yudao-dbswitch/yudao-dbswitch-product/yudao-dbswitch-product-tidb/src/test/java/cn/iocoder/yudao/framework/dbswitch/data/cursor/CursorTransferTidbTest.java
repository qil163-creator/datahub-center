package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CursorTransferTidbTest {
    private final String schema="cursor_tidb_"+UUID.randomUUID().toString().replace("-","");
    private final Map<ProductTypeEnum,DriverManagerDataSource> sources=new EnumMap<>(ProductTypeEnum.class);
    private final Map<ProductTypeEnum,Connection> controls=new EnumMap<>(ProductTypeEnum.class);
    private final AtomicReference<CursorCheckpoint> saved=new AtomicReference<>();
    private final List<String> fields=List.of("stamp","id","value");
    private final BatchLimits limits=new BatchLimits(2,65536,16384,65535,1000);
    private DbSwitchRuntime runtime;
    private cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin tidbPin;
    @BeforeEach void setup()throws Exception {
        runtime=DbSwitchRuntime.standalone();
        for(var product:List.of(ProductTypeEnum.TIDB,ProductTypeEnum.POSTGRESQL)) {
            String prefix="datahub.test."+(product==ProductTypeEnum.TIDB?"tidb":"postgres");
            var ds=new DriverManagerDataSource(System.getProperty(prefix+".url"),System.getProperty(prefix+".user",product==ProductTypeEnum.TIDB?"root":"dataforge_test"),System.getProperty(prefix+".password",""));
            ds.setDriverClassName(product.getDriveClassName());sources.put(product,ds);controls.put(product,ds.getConnection());
            sql(product,"CREATE "+(product==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+schema);
        }
        tidbPin=cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.initialize(controls.get(ProductTypeEnum.TIDB),schema,5);
    }
    @AfterEach void cleanup()throws Exception {
        if(runtime!=null)runtime.close();SQLException failure=null;
        for(var entry:controls.entrySet())try(var connection=entry.getValue();var statement=connection.createStatement()) {
            statement.execute("DROP "+(entry.getKey()==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+"IF EXISTS "+schema+(entry.getKey()==ProductTypeEnum.TIDB?"":" CASCADE"));
        }catch(SQLException e){failure=e;}
        if(failure!=null)throw failure;
    }
    private void create(ProductTypeEnum product,String table,String stamp,String key)throws Exception {
        sql(product,"CREATE TABLE "+schema+"."+table+"(stamp "+stamp+" NOT NULL,id "+key+" PRIMARY KEY,value varchar(80))");
    }
    private void tables(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"source","decimal(38,8)","bigint");create(target,"target","decimal(38,8)","bigint");
    }
    private void sql(ProductTypeEnum product,String sql)throws Exception{try(var s=controls.get(product).createStatement()){s.execute(sql);}}
    private long count(ProductTypeEnum product)throws Exception{try(var s=controls.get(product).createStatement();var r=s.executeQuery("SELECT count(*) FROM "+schema+".target")){assertTrue(r.next());return r.getLong(1);}}
    private boolean save(CursorCheckpoint checkpoint){saved.set(checkpoint);return true;}
    private void transfer(ProductTypeEnum source,ProductTypeEnum target,CursorRunBinding.CheckpointStore store)throws Exception {
        transfer(sources.get(source),sources.get(target),source,target,store);
    }
    private void transfer(DataSource source,DataSource target,ProductTypeEnum sourceProduct,ProductTypeEnum targetProduct,CursorRunBinding.CheckpointStore store)throws Exception {
        CursorTransfer.execute(runtime,source,target,sourceProduct,targetProduct,schema,"source",schema,"target",fields,fields,List.of("id"),List.of("id"),"stamp",limits,2,
                new CursorRunBinding("a".repeat(64),saved.get(),store,sourceProduct==ProductTypeEnum.TIDB?tidbPin:null),new DbSwitchTableResult(),()->{});
    }

    @ParameterizedTest @CsvSource({"TIDB,TIDB","TIDB,POSTGRESQL","POSTGRESQL,TIDB"})
    void committedBatchThenFailedCheckpointReplaysOnlyTheFrozenWindow(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        tables(source,target);for(int i=1;i<=5;i++)sql(source,"INSERT INTO "+schema+".source VALUES(10,"+i+",'original')");
        var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->transfer(source,target,next->calls.incrementAndGet()!=3&&save(next)));
        assertEquals(4,count(target));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());
        assertEquals(target==ProductTypeEnum.TIDB?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,saved.get().lastCommitAcknowledgement());
        if(target==ProductTypeEnum.TIDB)assertNull(saved.get().lastCommit());
        sql(source,"INSERT INTO "+schema+".source VALUES(10,0,'same watermark'),(11,100,'next window')");
        transfer(source,target,this::save);assertEquals(6,count(target));assertTrue(saved.get().complete());
        transfer(source,target,this::save);assertEquals(7,count(target));assertEquals(source,saved.get().sourceProduct());assertEquals(target,saved.get().targetProduct());
    }

    @ParameterizedTest @ValueSource(strings={"source","target"})
    void identicalDdlRecreationCannotReuseACheckpoint(String replaced)throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first')");
        transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save);var previous=saved.get();
        sql(ProductTypeEnum.TIDB,"DROP TABLE "+schema+"."+replaced);create(ProductTypeEnum.TIDB,replaced,"decimal(38,8)","bigint");
        if(replaced.equals("source"))sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(11,2,'recreated')");
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));assertSame(previous,saved.get());
        assertEquals(replaced.equals("target")?0:1,count(ProductTypeEnum.TIDB));
    }

    @ParameterizedTest @ValueSource(strings={"TIDB","POSTGRESQL"})
    void unsignedBigintIsExactInBothTheCursorAndTarget(String targetName)throws Exception {
        var target=ProductTypeEnum.valueOf(targetName);create(ProductTypeEnum.TIDB,"source","bigint unsigned","bigint unsigned");
        create(target,"target",target==ProductTypeEnum.TIDB?"bigint unsigned":"numeric(20,0)",target==ProductTypeEnum.TIDB?"bigint unsigned":"numeric(20,0)");
        sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(18446744073709551615,18446744073709551615,'exact')");
        transfer(ProductTypeEnum.TIDB,target,this::save);assertEquals("18446744073709551615",saved.get().fixedUpper().values().getFirst().value());
        try(var s=controls.get(target).createStatement();var r=s.executeQuery("SELECT stamp,id FROM "+schema+".target")) {
            assertTrue(r.next());assertEquals("18446744073709551615",r.getBigDecimal(1).toPlainString());assertEquals("18446744073709551615",r.getBigDecimal(2).toPlainString());
        }
    }

    @Test void independentPagesKeepOneTiDbSnapshotDespiteConcurrentChanges()throws Exception {
        create(ProductTypeEnum.TIDB,"source","bigint","bigint");sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first'),(10,2,'original')");
        var reader=runtime.reader(sources.get(ProductTypeEnum.TIDB),ProductTypeEnum.TIDB);reader.setFetchSize(1);
        try(var cursor=reader.openCursor(new CursorReadRequest(schema,"source",fields,"stamp",List.of("id"),null,null,null,limits,false,tidbPin))) {
            assertTrue(cursor.next());assertEquals(1L,cursor.row()[1]);
            sql(ProductTypeEnum.TIDB,"UPDATE "+schema+".source SET stamp=0,value='changed' WHERE id=2");
            assertTrue(cursor.next());assertEquals(2L,cursor.row()[1]);assertEquals("original",cursor.row()[2]);assertFalse(cursor.next());
        }
    }

    @ParameterizedTest @ValueSource(strings={"SET SESSION tidb_read_staleness=-1","SET SESSION tidb_replica_read='follower'"})
    void unsafeSessionModesStopBeforeCheckpointOrTargetWrite(String sessionSql)throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first')");
        DataSource altered=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{var c=sources.get(ProductTypeEnum.TIDB).getConnection();try(var s=c.createStatement()){s.execute(sessionSql);}return c;}
            @Override public Connection getConnection(String name,String secret)throws SQLException{return getConnection();}
        };
        assertThrows(IllegalArgumentException.class,()->transfer(altered,sources.get(ProductTypeEnum.TIDB),ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));
        assertNull(saved.get());assertEquals(0,count(ProductTypeEnum.TIDB));
    }

    @Test void actualCommitWithLostAcknowledgementNeverAdvancesTheCheckpoint()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first'),(10,2,'second')");
        DataSource fault=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {
                var actual=sources.get(ProductTypeEnum.TIDB).getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try{var value=method.invoke(actual,args);if(method.getName().equals("commit"))throw new SQLException("fixture lost commit acknowledgement");return value;}
                    catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                });
            }
            @Override public Connection getConnection(String name,String secret)throws SQLException{return getConnection();}
        };
        assertThrows(UnconfirmedWriteException.class,()->transfer(sources.get(ProductTypeEnum.TIDB),fault,ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));
        assertEquals(2,count(ProductTypeEnum.TIDB));assertEquals(0,saved.get().sequence());assertNull(saved.get().applied());
        transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save);assertEquals(2,count(ProductTypeEnum.TIDB));assertTrue(saved.get().complete());
    }

    @Test void competingUniqueIndexesCannotSelectAnUnrelatedReplayTarget()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"ALTER TABLE "+schema+".target ADD UNIQUE KEY other_key(value)");
        sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'conflict')");
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));assertNull(saved.get());assertEquals(0,count(ProductTypeEnum.TIDB));
    }

    @Test void frozenEmptyWindowCannotExpandDuringRecovery()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,next->calls.incrementAndGet()==1&&save(next)));
        assertNull(saved.get().fixedUpper());assertFalse(saved.get().complete());sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'later')");
        transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save);assertEquals(0,count(ProductTypeEnum.TIDB));
        transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save);assertEquals(1,count(ProductTypeEnum.TIDB));
    }

    @Test void snapshotAdmissionNeverCommitsAnAlreadyActiveTransaction()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);
        try(var connection=sources.get(ProductTypeEnum.TIDB).getConnection()) {
            connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("INSERT INTO "+schema+".target VALUES(10,1,'uncommitted')");}
            assertThrows(SQLException.class,()->cn.iocoder.yudao.framework.dbswitch.core.util.TidbRelationIdentity.beginOwnedSnapshot(connection,5));
            assertEquals(0,count(ProductTypeEnum.TIDB));connection.rollback();
        }
        assertEquals(0,count(ProductTypeEnum.TIDB));
    }

    @Test void nativeTextKeyEqualityMustMatchTheTargetCollation()throws Exception {
        create(ProductTypeEnum.TIDB,"source","bigint","varchar(20) COLLATE utf8mb4_bin");
        create(ProductTypeEnum.TIDB,"target","bigint","varchar(20) COLLATE utf8mb4_general_ci");
        sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,'a','lower'),(10,'A','upper')");
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));assertNull(saved.get());assertEquals(0,count(ProductTypeEnum.TIDB));
    }

    @Test void aFenceBetweenPagesRejectsFurtherRowsAndNeverPublishesCompletion()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first'),(10,2,'second'),(10,3,'third')");
        assertThrows(SQLException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,next->{
            save(next);
            if(next.sequence()==1)try{cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.fence(controls.get(ProductTypeEnum.TIDB),tidbPin,5);}catch(SQLException failure){throw new IllegalStateException(failure);}
            return true;
        }));
        assertEquals(2,count(ProductTypeEnum.TIDB));assertEquals(1,saved.get().sequence());assertFalse(saved.get().complete());
    }

    @Test void aRotatedGenerationCannotResumeAnOlderCheckpointEvenWithAnUnchangedBindingString()throws Exception {
        tables(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB);sql(ProductTypeEnum.TIDB,"INSERT INTO "+schema+".source VALUES(10,1,'first')");
        transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save);
        var admin=controls.get(ProductTypeEnum.TIDB);cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.fence(admin,tidbPin,5);
        tidbPin=cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.rotate(admin,tidbPin,UUID.randomUUID().toString(),5);
        assertThrows(IllegalArgumentException.class,()->transfer(ProductTypeEnum.TIDB,ProductTypeEnum.TIDB,this::save));assertEquals(1,count(ProductTypeEnum.TIDB));
    }

    @ParameterizedTest @CsvSource({"TIDB,TIDB","TIDB,POSTGRESQL","POSTGRESQL,TIDB"})
    void timestampWatermarkPreservesItsInstantAndMicroseconds(ProductTypeEnum source,ProductTypeEnum target)throws Exception {
        create(source,"source",source==ProductTypeEnum.TIDB?"timestamp(6)":"timestamptz(6)","bigint");
        create(target,"target",target==ProductTypeEnum.TIDB?"timestamp(6)":"timestamptz(6)","bigint");
        sql(ProductTypeEnum.TIDB,"SET SESSION time_zone='+00:00'");
        sql(ProductTypeEnum.POSTGRESQL,"SET TIME ZONE 'UTC'");
        sql(source,"INSERT INTO "+schema+".source VALUES('2026-03-08 02:30:00.123456',1,'instant')");
        transfer(source,target,this::save);
        assertEquals("2026-03-08T02:30:00.123456Z",saved.get().applied().values().getFirst().value());
        try(var s=controls.get(target).createStatement();var r=s.executeQuery("SELECT stamp FROM "+schema+".target")) {
            assertTrue(r.next());
            var actual=target==ProductTypeEnum.TIDB?r.getObject(1,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)
                    :r.getObject(1,java.time.OffsetDateTime.class).toInstant();
            assertEquals(java.time.Instant.parse("2026-03-08T02:30:00.123456Z"),actual);
        }
    }
}
