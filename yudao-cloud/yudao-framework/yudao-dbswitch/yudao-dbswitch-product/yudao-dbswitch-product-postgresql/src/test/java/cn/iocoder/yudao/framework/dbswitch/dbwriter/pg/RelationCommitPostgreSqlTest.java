package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class RelationCommitPostgreSqlTest {
    DriverManagerDataSource source;
    Connection control;
    String schema;

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"), "dataforge_test", "");
        control = source.getConnection();
        schema = "relreceipt_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id int)");
    }
    @AfterEach void cleanup() throws Exception {
        try {
            assertTrue(schema.matches("relreceipt_[a-f0-9]{32}"));
            sql("DROP SCHEMA " + schema + " CASCADE");
        } finally { control.close(); }
    }

    @ParameterizedTest
    @CsvSource({"same,true","same,false","truncate,true","truncate,false","recreate,true","recreate,false",
            "other,true","other,false","rewrite,true","rewrite,false","legacy,true","legacy,false"})
    void visibleCommitDoesNotHideTargetReplacement(String action, boolean insert) throws Exception {
        var receipt = write(insert);
        assertNotNull(receipt.relationFingerprint());
        String reference = schema + ".rows";
        String previous = PostgresRelationIdentity.fingerprint(control, reference);
        switch (action) {
            case "truncate" -> sql("TRUNCATE " + reference);
            case "recreate" -> { sql("DROP TABLE " + reference); sql("CREATE TABLE " + reference + "(id int)"); }
            case "other" -> { sql("CREATE TABLE " + schema + ".other(id int)"); reference = schema + ".other"; }
            case "rewrite" -> sql("VACUUM FULL " + reference);
            case "legacy" -> receipt = new TransactionCommitReceipt(1, receipt.protocol(), receipt.transactionId(), receipt.routeFingerprint());
            default -> { }
        }
        if (action.equals("truncate") || action.equals("rewrite"))
            assertEquals(previous, PostgresRelationIdentity.fingerprint(control, reference), "旧 OID 指纹不能区分存储重建");
        var collector = new BatchCommitSummary.Collector(); collector.add(1, receipt);
        try (var reader = source.getConnection();
             var snapshot = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(reader, 5)) {
            var result = snapshot.inspectCommitRanges(List.of(collector.snapshot().coverage()), reference);
            assertEquals("RANGE_VISIBLE", result.observations().getFirst().status());
            assertEquals(action.equals("same") ? "MATCH" : action.equals("legacy") ? "UNPROVEN" : "MISMATCH",
                    result.observations().getFirst().relationStatus());
        }
    }

    @Test void oldCatalogSnapshotCannotHideTruncate() throws Exception {
        var collector = new BatchCommitSummary.Collector(); collector.add(1, write(true));
        try (var reader = source.getConnection();
             var snapshot = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(reader, 5)) {
            String before = PostgresRelationIdentity.storageFingerprint(reader, schema + ".rows", false);
            sql("TRUNCATE " + schema + ".rows");
            assertNotEquals(before, PostgresRelationIdentity.storageFingerprint(reader, schema + ".rows", false));
            var result = snapshot.inspectCommitRanges(List.of(collector.snapshot().coverage()), schema + ".rows");
            assertEquals("RANGE_VISIBLE", result.observations().getFirst().status());
            assertEquals("MISMATCH", result.observations().getFirst().relationStatus());
        }
    }

    @ParameterizedTest @ValueSource(strings={"unlogged","view","partition","inheritance","rls"})
    void unsupportedRelationsKeepNormalWriteWithoutInventingStorageProof(String kind) throws Exception {
        sql("DROP TABLE " + schema + ".rows");
        switch (kind) {
            case "unlogged" -> sql("CREATE UNLOGGED TABLE " + schema + ".rows(id int)");
            case "view" -> { sql("CREATE TABLE " + schema + ".base(id int)"); sql("CREATE VIEW " + schema + ".rows AS SELECT * FROM " + schema + ".base"); }
            case "partition" -> {
                sql("CREATE TABLE " + schema + ".rows(id int) PARTITION BY RANGE(id)");
                sql("CREATE TABLE " + schema + ".child PARTITION OF " + schema + ".rows FOR VALUES FROM(0) TO(10)");
            }
            case "inheritance" -> {
                sql("CREATE TABLE " + schema + ".rows(id int)");
                sql("CREATE TABLE " + schema + ".child() INHERITS(" + schema + ".rows)");
            }
            default -> { sql("CREATE TABLE " + schema + ".rows(id int)"); sql("ALTER TABLE " + schema + ".rows ENABLE ROW LEVEL SECURITY"); }
        }
        var receipt = write(true); assertNotNull(receipt); assertNull(receipt.relationFingerprint());
        var collected = new BatchCommitSummary.Collector(); collected.add(1, receipt);
        try (var reader = source.getConnection();
             var snapshot = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(reader, 5)) {
            assertEquals("UNPROVEN", snapshot.inspectCommitRanges(List.of(collected.snapshot().coverage()), schema + ".rows")
                    .observations().getFirst().relationStatus());
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void replacementAfterCommitCannotPublishReceiptOrReplay(boolean insert) throws Exception {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(source, ProductTypeEnum.POSTGRESQL, insert ? cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT : cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        writer.prepareWrite(schema, "rows", List.of("id"));
        try (var physical = source.getConnection()) {
            var wrapped = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> {
                try {
                    Object result = m.invoke(physical,a);
                    if (m.getName().equals("commit")) sql("TRUNCATE " + schema + ".rows");
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
            var ds = mock(DataSource.class); when(ds.getConnection()).thenReturn(wrapped);
            ReflectionTestUtils.setField(writer,"dataSource",ds);
            assertThrows(UnconfirmedWriteException.class,
                    () -> writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})));
            verify(ds,times(1)).getConnection();
            try (var s = control.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
                assertTrue(r.next()); assertEquals(0, r.getInt(1), "不能在清空之后隐式重发");
            }
        }
    }

    @Test void quotedIdentifiersAndMissingRelationAreIsolatedByCheckpoint() throws Exception {
        sql("CREATE TABLE " + schema + ".\"odd\"\";name\"(id int)");
        String ref = schema + ".\"odd\"\";name\"";
        try (var reader = source.getConnection();
             var snapshot = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(reader,5)) {
            assertNotNull(PostgresRelationIdentity.storageFingerprint(reader,ref,true));
            var collected = new BatchCommitSummary.Collector(); collected.add(1, write(true));
            assertThrows(SQLException.class, () -> snapshot.inspectCommitRanges(List.of(collected.snapshot().coverage()), schema + ".missing"));
            assertEquals("MISMATCH", snapshot.inspectCommitRanges(List.of(collected.snapshot().coverage()), ref)
                    .observations().getFirst().relationStatus());
            try (var s = reader.createStatement(); var r = s.executeQuery("SELECT 1")) { assertTrue(r.next()); }
        }
    }

    TransactionCommitReceipt write(boolean insert) {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(source, ProductTypeEnum.POSTGRESQL, insert ? cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT : cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        writer.prepareWrite(schema, "rows", List.of("id"));
        return writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})).receipt();
    }
    void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); } }
}
