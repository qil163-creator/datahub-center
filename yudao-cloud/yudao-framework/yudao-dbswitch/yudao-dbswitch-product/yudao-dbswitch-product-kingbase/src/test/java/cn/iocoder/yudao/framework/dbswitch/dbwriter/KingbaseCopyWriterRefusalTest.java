package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase.KingbaseCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.kingbase.KingbaseProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingbaseCopyWriterRefusalTest {
    @Test
    void unsupportedCopyIsRefusedBeforeGreenplumProtocol() throws Exception {
        var kingbase = new KingbaseProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.KINGBASE)
                .findFirst().orElseThrow();
        var copy = kingbase.capabilities().get(DatabaseOperation.WRITE_COPY);
        assertEquals(OperationCapability.Status.UNSUPPORTED, copy.status());
        assertEquals("OPERATION_NOT_IMPLEMENTED", copy.reason());

        try (var input = KingbaseProductProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/dbswitch/products/kingbase.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int marker = json.indexOf("\"WRITE_COPY\"");
            String block = json.substring(marker, json.indexOf('}', marker));
            assertTrue(block.contains("\"status\": \"UNSUPPORTED\""));
            assertTrue(block.contains("OPERATION_NOT_IMPLEMENTED"));
            assertFalse(block.contains("VERIFIED"));
        }

        var calls = new AtomicInteger();
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new AssertionError(method.getName());
                });
        var writer = new KingbaseCopyWriterImpl(source);
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.write(List.of("id"), List.<Object[]>of(new Object[] {1})));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("Kingbase"));
        assertFalse(failure.getMessage().contains("Greenplum"));
        assertEquals(0, calls.get());
    }
}
