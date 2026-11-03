package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.product.uxdb.UxdbProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 无库：WRITE_COPY 未验证时写入入口必须拒绝，且不借连接。 */
class UxdbCopyWriterRefusalTest {
    @Test void declaredCopyIsRefusedBeforeAnyConnectionOrCopyStatement() throws Exception {
        var uxdb = new UxdbProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.UXDB)
                .findFirst().orElseThrow();
        var copy = uxdb.capabilities().get(DatabaseOperation.WRITE_COPY);
        var insert = uxdb.capabilities().get(DatabaseOperation.WRITE_INSERT);
        assertEquals(OperationCapability.Status.DECLARED, copy.status());
        assertEquals("IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION", copy.reason());
        assertEquals(OperationCapability.Status.DECLARED, insert.status());
        assertEquals("IMPLEMENTATION_REQUIRES_DATABASE_CERTIFICATION", insert.reason());

        try (var input = UxdbProductProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/dbswitch/products/uxdb.json")) {
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
        var writer = new UxdbCopyWriterImpl(source);
        List<Object[]> rows = List.<Object[]>of(new Object[] {1});
        assertDoesNotThrow(() -> writer.preflightTargetDefinition("s", "rows",
                List.of("CREATE TABLE s.rows(id integer)"), List.of("id")));

        for (SQLFeatureNotSupportedException failure : List.of(
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id"), rows)),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.writeWithReceipt(List.of("id"), rows)),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.writeWithReceipt(List.of("id"), List.of())),
                assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(null, null)))) {
            assertEquals("0A000", failure.getSQLState());
            assertTrue(failure.getMessage().contains("not verified"));
            assertTrue(failure.getMessage().contains("runtime profile"));
            assertFalse(failure.getMessage().contains("STDIN"));
            assertFalse(failure.getMessage().contains("CopyManager"));
        }
        assertEquals(0, calls.get());
    }

    @Test void unsupportedCreatesFailPurelyBeforeAnyConnection() {
        DataSource source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                (proxy,method,args)->{throw new AssertionError(method.getName());});
        var writer=new UxdbCopyWriterImpl(source);
        for(String type:List.of("integer[]","timestamp with time zone","time with time zone","jsonb","uuid","numeric","numeric(1001,0)","bigint GENERATED ALWAYS AS IDENTITY"))
            assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightTargetDefinition("s","t",List.of("CREATE TABLE s.t(id "+type+")"),List.of("id")));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightTargetDefinition("s","t",List.of("CREATE TABLE s.t(id integer DEFAULT 1)"),List.of("id")));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightTargetDefinition("s","t",List.of("CREATE TABLE s.other(id integer)"),List.of("id")));
        assertThrows(SQLFeatureNotSupportedException.class,()->writer.preflightTargetDefinition("s","t",List.of("CREATE TABLE s.t(id integer,hidden integer)"),List.of("id")));
    }
}
