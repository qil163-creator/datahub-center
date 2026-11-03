package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumCopyWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.sql.DriverManager;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CopyCommitReceiptPostgreSqlTest {
    HikariDataSource pool;
    Connection control;
    String schema;
    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource();
        pool.setJdbcUrl(System.getProperty("datahub.test.postgres.url") + "?socketTimeout=5");
        pool.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        pool.setPassword(System.getProperty("datahub.test.postgres.password", ""));
        pool.setMaximumPoolSize(3); pool.setMinimumIdle(0);
        control = pool.getConnection(); schema = "copyreceipt_" + UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA " + schema); sql("CREATE TABLE " + schema + ".rows(id int CHECK(id > 0))");
    }
    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        try {
            if (control != null) try (var ownedControl = control) {
                assertTrue(schema.matches("copyreceipt_[a-f0-9]{32}")); sql("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally { if (pool != null) pool.close(); }
    }
    @Test void actualCopyCommitReceiptBindsWrittenRowsAndOldOrNewSnapshot() throws Exception {
        var writer = writer();
        try (var reader = pool.getConnection()) {
            var dialect = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
            TransactionCommitReceipt receipt;
            try (var snapshot = dialect.openReadSnapshot(reader,5)) {
                var result = writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{1}));
                assertEquals(1,result.acknowledgedRows());
                receipt = result.receipt(); assertNotNull(receipt,"实际 COPY 提交必须生成原生事务回执");
                try (var s = control.createStatement(); var rows = s.executeQuery("SELECT xmin::text FROM " + schema + ".rows")) {
                    assertTrue(rows.next()); assertEquals(receipt.transactionId(),rows.getString(1)); assertFalse(rows.next());
                }
                assertEquals("NOT_VISIBLE",snapshot.inspectCommits(List.of(receipt)).observations().getFirst().status());
            }
            try (var snapshot = dialect.openReadSnapshot(reader,5)) {
                assertEquals("VISIBLE",snapshot.inspectCommits(List.of(receipt)).observations().getFirst().status());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"copy-start","copy-write","copy-end-before","copy-end-after","cancel-failed",
            "commit-lost","commit-abort","restore","close","status","mid-commit","mid-rollback","interrupt","transaction-check"})
    void everyUnknownExitDiscardsReceiptAndNeverReplays(String fault) throws Exception {
        var writer = writer(); var copies = new AtomicInteger(); var inserts = new AtomicInteger();
        var samples = new AtomicInteger(); var rollbacks = new AtomicInteger();
        try (var physical = DriverManager.getConnection(pool.getJdbcUrl(),pool.getUsername(),pool.getPassword())) {
            var pg = physical.unwrap(PGConnection.class); var manager = mock(CopyManager.class);
            when(manager.copyIn(anyString())).thenAnswer(call -> {
                copies.incrementAndGet();
                CopyIn operation = pg.getCopyAPI().copyIn(call.getArgument(0));
                if (fault.equals("copy-start")) throw privateFailure();
                return Proxy.newProxyInstance(CopyIn.class.getClassLoader(),new Class<?>[]{CopyIn.class},(p,m,a) -> {
                    String name = m.getName();
                    if (name.equals("endCopy") && List.of("copy-end-before","cancel-failed").contains(fault)) throw privateFailure();
                    if (name.equals("cancelCopy") && fault.equals("cancel-failed")) throw privateFailure();
                    Object value = invoke(m,operation,a);
                    if (name.equals("writeToCopy") && fault.equals("copy-write")) throw privateFailure();
                    if (name.equals("endCopy")) {
                        if (fault.equals("copy-end-after")) throw privateFailure();
                        if (fault.equals("mid-commit")) physical.commit();
                        if (fault.equals("mid-rollback")) physical.rollback();
                        if (fault.equals("interrupt")) Thread.currentThread().interrupt();
                    }
                    return value;
                });
            });
            var wrappedPg = (PGConnection)Proxy.newProxyInstance(PGConnection.class.getClassLoader(),new Class<?>[]{PGConnection.class},
                    (p,m,a) -> m.getName().equals("getCopyAPI") ? manager : invoke(m,pg,a));
            var wrapped = (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a) -> {
                String name = m.getName();
                if (name.equals("unwrap") && a[0] == PGConnection.class) return wrappedPg;
                if (name.equals("isWrapperFor") && a[0] == PGConnection.class) return true;
                if (name.equals("rollback")) rollbacks.incrementAndGet();
                if (name.equals("commit") && fault.equals("commit-lost")) { physical.commit(); throw privateFailure(); }
                if (name.equals("commit") && fault.equals("commit-abort")) { physical.rollback(); return null; }
                if (name.equals("setAutoCommit") && Boolean.TRUE.equals(a[0]) && fault.equals("restore")) throw privateFailure();
                if (name.equals("close") && fault.equals("close")) throw privateFailure();
                if (name.equals("prepareStatement")) {
                    String sql = (String)a[0];
                    if (sql.startsWith("INSERT")) inserts.incrementAndGet();
                    if (sql.contains("pg_xact_status") && fault.equals("status")) throw privateFailure();
                }
                if (name.equals("createStatement")) {
                    var statement = physical.createStatement();
                    return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(), new Class<?>[]{java.sql.Statement.class}, (sp, sm, sa) -> {
                        if (sm.getName().equals("executeQuery") && ((String)sa[0]).contains("pg_current_xact_id()")) {
                            if (samples.incrementAndGet() == 2 && fault.equals("transaction-check")) throw privateFailure();
                        }
                        return invoke(sm, statement, sa);
                    });
                }
                return invoke(m,physical,a);
            });
            var source = mock(DataSource.class); when(source.getConnection()).thenReturn(wrapped);
            ReflectionTestUtils.setField(writer,"dataSource",source);
            var failure = assertThrows(UnconfirmedWriteException.class,() -> writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{1})));
            Thread.interrupted();
            assertEquals(1,copies.get()); assertEquals(0,inserts.get()); verify(source,times(1)).getConnection();
            assertNull(failure.getCause()); assertEquals(0,failure.getSuppressed().length);
            assertFalse(failure.getMessage().contains("private"));
            assertEquals(List.of("commit-lost","restore","close","status","mid-commit").contains(fault) ? 1 : 0,count());
            if (List.of("cancel-failed","copy-start").contains(fault)) {
                assertEquals(0,rollbacks.get(),"活动 COPY 不能发送可能等待自身的 rollback");
                assertEquals(1,samples.get(),"活动或失败 COPY 不能继续查询提交见证");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"short","constraint","conversion"})
    void incompleteCopyHasNoReceiptAndEvictsItsSession(String fault) throws Exception {
        var writer = writer(); int before;
        if (fault.equals("short")) {
            sql("CREATE FUNCTION " + schema + ".skip_second() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.id=2 THEN RETURN NULL; END IF; RETURN NEW; END'");
            sql("CREATE TRIGGER skip_second BEFORE INSERT ON " + schema + ".rows FOR EACH ROW EXECUTE FUNCTION " + schema + ".skip_second()");
        }
        try (var c = pool.getConnection()) { before = pid(c); }
        var records = fault.equals("short") ? List.of(new Object[]{1},new Object[]{2})
                : fault.equals("constraint") ? List.of(new Object[]{1},new Object[]{-1})
                : List.of(new Object[]{1},new Object[]{"invalid-int"});
        if (fault.equals("conversion")) {
            assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(List.of("id"),records));
        } else {
            assertThrows(UnconfirmedWriteException.class,() -> writer.writeWithReceipt(List.of("id"),records));
        }
        assertEquals(0,count());
        try (var c = pool.getConnection()) {
            assertTrue(c.getAutoCommit());
            if (fault.equals("conversion")) assertEquals(before,pid(c));
            else assertNotEquals(before,pid(c));
        }
    }

    @Test void copyCompletionCannotSubstituteForDeferredConstraintCommit() throws Exception {
        sql("ALTER TABLE " + schema + ".rows ADD CONSTRAINT unique_id UNIQUE(id) DEFERRABLE INITIALLY DEFERRED");
        var writer = writer();
        assertThrows(UnconfirmedWriteException.class,() -> writer.writeWithReceipt(List.of("id"),
                List.of(new Object[]{1},new Object[]{1})));
        assertEquals(0,count());
    }

    @Test void laterFailureDoesNotEraseEarlierBatchesAndLegacyWriteRemainsUsable() throws Exception {
        var writer = writer();
        assertEquals(1,writer.write(List.of("id"),List.<Object[]>of(new Object[]{1})));
        var first = writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{2}));
        var second = writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{3}));
        assertNotNull(first.receipt()); assertNotNull(second.receipt());
        assertNotEquals(first.receipt().transactionId(),second.receipt().transactionId());
        assertThrows(UnconfirmedWriteException.class,() -> writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{-1})));
        assertEquals(3,count());
    }

    @Test void greenplumDeclarationCannotBorrowPgFixtureAdmissionOrReceipt() throws Exception {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.GREENPLUM, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        assertEquals(GreenplumCopyWriterImpl.class,writer.getClass());
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> writer.prepareWrite(schema,"rows",List.of("id")));
        assertThrows(java.sql.SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{1})));
        assertEquals(0,count());
    }

    @ParameterizedTest @ValueSource(strings={"unverified-version","product-mismatch","existing-transaction","metadata-failure"})
    void unverifiedProductOrConnectionCannotInventReceipt(String fault) throws Exception {
        var writer = writer();
        try (var physical = DriverManager.getConnection(pool.getJdbcUrl(),pool.getUsername(),pool.getPassword())) {
            var actualMetadata = physical.getMetaData();
            var metadata = (java.sql.DatabaseMetaData)Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{java.sql.DatabaseMetaData.class}, (p,m,a) -> {
                        if (m.getName().equals("getDatabaseProductName") && fault.equals("product-mismatch")) return "Greenplum";
                        if (m.getName().equals("getDatabaseMajorVersion") && fault.equals("unverified-version")) return 15;
                        return invoke(m,actualMetadata,a);
                    });
            if (fault.equals("existing-transaction")) physical.setAutoCommit(false);
            var commits = new AtomicInteger(); var copies = new AtomicInteger();
            var pg = physical.unwrap(PGConnection.class);
            var copyFacade = (PGConnection)Proxy.newProxyInstance(PGConnection.class.getClassLoader(),new Class<?>[]{PGConnection.class},(p,m,a) -> {
                if (m.getName().equals("getCopyAPI")) copies.incrementAndGet();
                return invoke(m,pg,a);
            });
            var wrapped = (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a) -> {
                if (m.getName().equals("commit")) commits.incrementAndGet();
                if (m.getName().equals("unwrap") && a[0] == PGConnection.class) return copyFacade;
                if (m.getName().equals("isWrapperFor") && a[0] == PGConnection.class) return true;
                if (m.getName().equals("getMetaData")) {
                    if (fault.equals("metadata-failure")) throw privateFailure();
                    return metadata;
                }
                return invoke(m,physical,a);
            });
            var source = mock(DataSource.class); when(source.getConnection()).thenReturn(wrapped);
            ReflectionTestUtils.setField(writer,"dataSource",source);
            assertThrows(IllegalStateException.class,() -> writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{1})));
            assertEquals(0,count()); assertEquals(0,commits.get()); assertEquals(0,copies.get());
        }
    }

    @Test void ordinarySelectInsertPrincipalCanWitnessCopyWithoutElevatedPrivileges() throws Exception {
        String actor = "copy_actor_" + UUID.randomUUID().toString().replace("-","");
        assertTrue(actor.matches("copy_actor_[a-f0-9]{32}"));
        sql("CREATE ROLE " + actor + " LOGIN");
        try {
            sql("GRANT USAGE ON SCHEMA " + schema + " TO " + actor);
            sql("GRANT SELECT,INSERT ON " + schema + ".rows TO " + actor);
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(pool.getJdbcUrl(),actor,"");
            var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(source, ProductTypeEnum.POSTGRESQL, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
            writer.prepareWrite(schema,"rows",List.of("id"));
            var result = writer.writeWithReceipt(List.of("id"),List.<Object[]>of(new Object[]{1}));
            assertNotNull(result.receipt());
            try (var c = source.getConnection();
                 var snapshot = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(c,5)) {
                assertEquals("VISIBLE",snapshot.inspectCommits(List.of(result.receipt())).observations().getFirst().status());
                var summary = new cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary.Collector();
                summary.add(result.acknowledgedRows(),result.receipt());
                assertEquals("MATCH",snapshot.inspectCommitRanges(List.of(summary.snapshot().coverage()),schema + ".rows")
                        .observations().getFirst().relationStatus());
            }
        } finally {
            sql("REVOKE ALL ON " + schema + ".rows FROM " + actor);
            sql("REVOKE ALL ON SCHEMA " + schema + " FROM " + actor);
            sql("DROP ROLE " + actor);
        }
    }

    @Test void emptyAndMalformedBatchNeverBorrowConnection() {
        var source = mock(DataSource.class); var writer = writer();
        ReflectionTestUtils.setField(writer,"dataSource",source);
        assertNull(writer.writeWithReceipt(List.of("id"),List.of()).receipt());
        assertEquals(0,writer.writeWithReceipt(List.of(),List.of()).acknowledgedRows());
        assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(null,List.of()));
        assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(List.of("id"),null));
        assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(List.of(),List.<Object[]>of(new Object[]{1})));
        assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(List.of("id"),List.of(new Object[]{1},new Object[]{2,3})));
        assertThrows(IllegalArgumentException.class,() -> writer.writeWithReceipt(List.of("id"),java.util.Arrays.asList(new Object[]{1},null)));
        verifyNoInteractions(source);
    }

    static SQLException privateFailure() { return new SQLException("fixture-private-copy-reply"); }
    static Object invoke(Method m,Object target,Object[] args) throws Throwable {
        try { return m.invoke(target,args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
    }
    static int pid(Connection c) throws Exception {
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
    IDatabaseWriter writer() {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.POSTGRESQL, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        assertInstanceOf(PostgresCopyWriterImpl.class,writer);
        writer.prepareWrite(schema,"rows",List.of("id")); return writer;
    }
    void sql(String text) throws Exception { try (var s = control.createStatement()) { s.execute(text); } }
    long count() throws Exception {
        try (var s = control.createStatement(); var rows = s.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
