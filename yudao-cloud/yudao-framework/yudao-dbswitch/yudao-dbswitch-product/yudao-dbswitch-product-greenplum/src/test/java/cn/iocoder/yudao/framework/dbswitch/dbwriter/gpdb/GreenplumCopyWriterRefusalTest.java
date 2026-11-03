package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A declaration never grants COPY: an unprepared writer must reject before borrowing a connection. */
class GreenplumCopyWriterRefusalTest {
    @Test void nativePreflightReusesValidatedModelWithoutBorrowingAndRejectsForgedFields() {
        var calls=new AtomicInteger();
        DataSource source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{calls.incrementAndGet();throw new AssertionError(method.getName());});
        var writer=new GreenplumCopyWriterImpl(source);
        var column=new cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.Column(
                "id","bigint",false,null,null,Map.of());
        var definition=new cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables.Definition(
                "heap",Map.of(),"p",List.of("id"),List.of(column),List.of(),List.of(),null);
        var json=cn.hutool.json.JSONUtil.createObj().set("cluster","a".repeat(64)).set("oid","1")
                .set("definition",cn.hutool.json.JSONUtil.parseObj(definition));
        var valid=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(
                ProductTypeEnum.GREENPLUM,"GREENPLUM_7_TABLE_V1",json.toString(),List.of("id"));
        writer.preflightNativeTarget(valid,Map.of("id","renamed\" id"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightNativeTarget(valid,Map.of("other","id")));
        var forged=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(
                valid.product(),valid.format(),valid.document(),List.of("other"));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightNativeTarget(forged,Map.of("other","id")));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightNativeTarget(null,Map.of()));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.truncatePreparedTarget("s","t"));
        assertEquals(0,calls.get());
    }

    @Test void declaredCopyIsRefusedBeforeAnyConnectionOrCopyStatement() throws Exception {
        var greenplum = new GreenplumProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.GREENPLUM)
                .findFirst().orElseThrow();
        var copy = greenplum.capabilities().get(DatabaseOperation.WRITE_COPY);
        var insert = greenplum.capabilities().get(DatabaseOperation.WRITE_INSERT);
        assertEquals(OperationCapability.Status.DECLARED, copy.status());
        assertEquals("IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION", copy.reason());
        assertEquals(OperationCapability.Status.DECLARED, insert.status());
        assertEquals("IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION", insert.reason());

        try (var input = GreenplumProductProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/dbswitch/products/greenplum.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int marker = json.indexOf("\"WRITE_COPY\"");
            assertTrue(marker > json.indexOf("\"WRITE_INSERT\""));
            String block = json.substring(marker, json.indexOf('}', marker));
            assertTrue(block.contains("\"status\": \"DECLARED\""));
            assertTrue(block.contains("IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION"));
            assertFalse(block.contains("VERIFIED"));
        }

        var calls = new AtomicInteger();
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new AssertionError(method.getName());
                });
        var writer = new GreenplumCopyWriterImpl(source);
        List<Object[]> rows = List.<Object[]>of(new Object[] {1});
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.preflightTargetDefinition("s", "rows",
                List.of("CREATE TABLE s.rows(id integer)"), List.of("id")));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> writer.preflightTargetDefinition("s", "rows",
                List.of("CREATE TABLE s.rows(id integer) DISTRIBUTED BY(id)"), List.of("id")));

        for (SQLFeatureNotSupportedException failure : List.of(
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id"), rows)),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.writeWithReceipt(List.of("id"), rows)),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.writeWithReceipt(List.of("id"), List.of())),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(null, null)))) {
            assertEquals("0A000", failure.getSQLState());
            assertTrue(failure.getMessage().contains("not verified"));
            assertTrue(failure.getMessage().contains("runtime profile"));
            assertTrue(failure.getMessage().contains("Greenplum WRITE_COPY"));
            assertFalse(failure.getMessage().contains("STDIN"));
            assertFalse(failure.getMessage().contains("CopyManager"));
        }

        var viaSuper = new SuperWrite(source);
        SQLFeatureNotSupportedException inherited = assertThrows(SQLFeatureNotSupportedException.class,
                () -> viaSuper.write(List.of("id"), rows));
        assertEquals("0A000", inherited.getSQLState());
        assertTrue(inherited.getMessage().contains("Greenplum WRITE_COPY runtime profile"));

        var postgres = new PostgresCopyWriterImpl(source);
        for (SQLFeatureNotSupportedException failure : List.of(
                assertThrows(SQLFeatureNotSupportedException.class, () -> postgres.write(List.of("id"), rows)),
                assertThrows(SQLFeatureNotSupportedException.class, () -> postgres.writeWithReceipt(List.of("id"), rows)))) {
            assertEquals("0A000", failure.getSQLState());
            assertTrue(failure.getMessage().contains("PostgreSQL WRITE_COPY is not verified"));
            assertFalse(failure.getMessage().contains("Greenplum WRITE_COPY"));
        }
        assertEquals(0, calls.get());
    }

    @Test void unavailableRuntimeFailsWithSafeProductSpecificReason() {
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new java.sql.SQLException("synthetic private connection detail");
                });
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> new GreenplumCopyWriterImpl(source).preflightRuntimeWrite());
        assertEquals("0A000", failure.getSQLState()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
    }

    /** 覆盖 write 后仍调用 super 时，必须撞上 Greenplum 自己的拒绝。 */
    private static final class SuperWrite extends GreenplumCopyWriterImpl {
        private SuperWrite(DataSource dataSource) { super(dataSource); }
        @Override public long write(List<String> fieldNames, List<Object[]> recordValues) {
            return super.write(fieldNames, recordValues);
        }
    }
}
