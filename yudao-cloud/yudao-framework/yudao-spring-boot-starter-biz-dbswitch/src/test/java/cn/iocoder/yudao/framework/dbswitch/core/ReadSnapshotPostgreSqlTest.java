package cn.iocoder.yudao.framework.dbswitch.core;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class ReadSnapshotPostgreSqlTest {
    @AfterEach void clearInterrupt() {Thread.interrupted();}

    @Test void stableSnapshotRestoresOwnedConnectionAndNextScopeSeesNewCommit() throws Exception {
        String table="snapshot_"+UUID.randomUUID().toString().replace("-", "");
        try(var reader=open();var writer=open();var sql=writer.createStatement()) {
            sql.execute("CREATE TABLE "+table+"(id int)");
            try {
                sql.execute("INSERT INTO "+table+" VALUES(1)");
                var dialect=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
                String fingerprint;
                try(var snapshot=dialect.openReadSnapshot(reader,5)) {
                    fingerprint=snapshot.evidence().fingerprint();assertTrue(fingerprint.matches("[a-f0-9]{64}"));
                    assertEquals(1,snapshot.evidence().schemaVersion());
                    assertEquals("POSTGRESQL_REPEATABLE_READ_V1",snapshot.evidence().protocol());
                    assertEquals(1,count(reader,table));
                    sql.execute("INSERT INTO "+table+" VALUES(2)");
                    assertEquals(1,count(reader,table));
                    assertTrue(reader.isReadOnly());assertFalse(reader.getAutoCommit());
                }
                assertTrue(reader.getAutoCommit());assertFalse(reader.isReadOnly());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED,reader.getTransactionIsolation());
                try(var second=dialect.openReadSnapshot(reader,5)) {
                    assertNotEquals(fingerprint,second.evidence().fingerprint());
                    assertEquals(2,count(reader,table));
                }
            } finally {sql.execute("DROP TABLE "+table);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"commit","rollback","autocommit","readwrite"})
    void endingOrChangingTransactionCannotPublishEvidence(String fault) throws Exception {
        try(var connection=open()) {
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5);
            switch(fault) {
                case "commit" -> connection.commit();
                case "rollback" -> connection.rollback();
                case "autocommit" -> connection.setAutoCommit(true);
                case "readwrite" -> {connection.rollback();try(var sql=connection.createStatement()){sql.execute("SET TRANSACTION READ WRITE");}}
            }
            var failure=assertThrows(SQLException.class,snapshot::close);
            assertNull(failure.getCause());
            if (fault.equals("autocommit")) assertTrue(connection.isClosed());
            else assertTrue(connection.getAutoCommit());
            snapshot.close(); // 清理幂等，不开启新快照。
        }
    }

    @Test void callerTransactionIsNotAdoptedRolledBackOrCommitted() throws Exception {
        try(var connection=open();var sql=connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute("CREATE TEMP TABLE owned_snapshot(id int)");sql.execute("INSERT INTO owned_snapshot VALUES(1)");
            assertThrows(SQLException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5));
            assertFalse(connection.getAutoCommit());assertEquals(1,count(connection,"owned_snapshot"));
            connection.rollback();
        }
    }

    @Test void restoresNonDefaultReadOnlyAndIsolation() throws Exception {
        try(var connection=open()) {
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5)) {
                assertNotNull(snapshot.evidence());
            }
            assertTrue(connection.getAutoCommit());assertTrue(connection.isReadOnly());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE,connection.getTransactionIsolation());
        }
    }

    @Test void optionalQueryErrorCanBeRolledBackWithoutChangingTheReadSnapshot() throws Exception {
        try(var connection=open();var sql=connection.createStatement();
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5)) {
            try(var checkpoint=snapshot.checkpoint()) {
                assertThrows(SQLException.class,()->sql.executeQuery("SELECT 1/0"));
            }
            try(var result=sql.executeQuery("SELECT 2")) {assertTrue(result.next());assertEquals(2,result.getInt(1));}
            assertNotNull(snapshot.evidence());
            // close 将复验原 backend/virtualxid/native snapshot，不是新事务中的“恢复成功”。
        }
    }

    @Test void abortedPooledConnectionIsNotReusedAfterRollbackFailure() throws Exception {
        var config=new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));config.setUsername("dataforge_test");
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(3000);
        try(var pool=new com.zaxxer.hikari.HikariDataSource(config)) {
            int oldBackend;
            try(var raw=pool.getConnection()) {
                oldBackend=backend(raw);
                var proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("rollback"))throw new SQLException("fixture failed rollback");
                    try{return m.invoke(raw,a);}catch(InvocationTargetException ex){throw ex.getCause();}
                });
                var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(proxy,5);
                assertThrows(SQLException.class,snapshot::close);assertTrue(raw.isClosed());
            }
            try(var next=pool.getConnection()) {
                assertNotEquals(oldBackend,backend(next));assertTrue(next.getAutoCommit());assertFalse(next.isReadOnly());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"sql","runtime"})
    void checkpointCleanupFailureClosesSessionAndInvalidatesTheWholeSnapshot(String kind) throws Exception {
        try(var raw=open()) {
            var proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("rollback") && a!=null && a.length==1) {
                    if(kind.equals("runtime"))throw new IllegalStateException("fixture-private");
                    throw new SQLException("fixture-private");
                }
                try{return m.invoke(raw,a);}catch(InvocationTargetException ex){throw ex.getCause();}
            });
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(proxy,5);
            var checkpoint=snapshot.checkpoint();
            var failure=assertThrows(SQLException.class,checkpoint::close);
            assertNull(failure.getCause());assertFalse(failure.getMessage().contains("private"));
            assertTrue(raw.isClosed());assertThrows(SQLException.class,snapshot::close);
        }
    }

    private int backend(Connection connection) throws SQLException {
        try(var sql=connection.createStatement();var result=sql.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(result.next());return result.getInt(1);
        }
    }

    @Test void actualReadOnlyTransactionRejectsWritesAndFailedReadCannotBePublished() throws Exception {
        try(var connection=open();var sql=connection.createStatement()) {
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5);
            assertThrows(SQLException.class,()->sql.execute("CREATE TABLE snapshot_forbidden(id int)"));
            assertThrows(SQLException.class,snapshot::close);
            assertTrue(connection.getAutoCommit());assertFalse(connection.isReadOnly());
        }
    }

    @ParameterizedTest @ValueSource(strings={"rollback","restore-auto","restore-isolation","restore-readonly"})
    void cleanupFailureInvalidatesEvidenceAndClosesConnection(String fault) throws Exception {
        try(var raw=open()) {
            var restores=new AtomicInteger();
            var proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                boolean restore=m.getName().equals("setAutoCommit") && Boolean.TRUE.equals(a[0]);
                if(restore) restores.incrementAndGet();
                if(fault.equals("rollback") && m.getName().equals("rollback")
                        || fault.equals("restore-auto") && restore
                        || fault.equals("restore-isolation") && m.getName().equals("setTransactionIsolation") && a[0].equals(Connection.TRANSACTION_READ_COMMITTED)
                        || fault.equals("restore-readonly") && m.getName().equals("setReadOnly") && Boolean.FALSE.equals(a[0]))
                    throw new SQLException("fixture-private-cleanup-detail");
                try {return m.invoke(raw,a);} catch(InvocationTargetException ex){throw ex.getCause();}
            });
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(proxy,5);
            var failure=assertThrows(SQLException.class,snapshot::close);
            assertNull(failure.getCause());assertFalse(failure.getMessage().contains("private"));
            assertTrue(raw.isClosed());
            if(fault.equals("rollback")) assertEquals(0,restores.get(),"回滚失败不能开启 autoCommit");
        }
    }

    @Test void initializationFailureDiscardsSessionAndInterruptedCloseRetainsSignal() throws Exception {
        try(var raw=open()) {
            var proxy=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("setTransactionIsolation"))throw new SQLException("fixture-private-init");
                try{return m.invoke(raw,a);}catch(InvocationTargetException ex){throw ex.getCause();}
            });
            assertThrows(SQLException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(proxy,5));
            assertTrue(raw.isClosed());
        }
        try(var raw=open()) {
            var snapshot=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(raw,5);
            Thread.currentThread().interrupt();assertThrows(SQLException.class,snapshot::close);
            assertTrue(Thread.currentThread().isInterrupted());
        }
    }

    @Test void postgresCompatibleProductsDoNotInheritVerifiedSnapshotCapability() throws Exception {
        try(var connection=open()) {
            for(var product:new ProductTypeEnum[]{ProductTypeEnum.UXDB,ProductTypeEnum.GREENPLUM,ProductTypeEnum.OPENGAUSS,ProductTypeEnum.MYSQL}) {
                assertThrows(SQLFeatureNotSupportedException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product).openReadSnapshot(connection,5));
                assertTrue(connection.getAutoCommit());assertFalse(connection.isReadOnly());
            }
        }
    }

    private long count(Connection connection,String table) throws SQLException {
        try(var sql=connection.createStatement();var result=sql.executeQuery("SELECT count(*) FROM "+table)) {
            assertTrue(result.next());return result.getLong(1);
        }
    }
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),"dataforge_test","");
    }
}
