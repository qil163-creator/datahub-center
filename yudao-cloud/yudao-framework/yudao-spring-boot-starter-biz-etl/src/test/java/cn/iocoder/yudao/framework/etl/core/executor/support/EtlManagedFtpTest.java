package cn.iocoder.yudao.framework.etl.core.executor.support;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EtlManagedFtpTest {
    @Test void refusesUrlCredentialsTraversalAndUnboundNetworkBeforeConnect() {
        for (String path : new String[]{"../secret", "/absolute", "folder//file", "folder/./file", "folder/a\r\nRETR secret", "*.csv"})
            assertThrows(IllegalArgumentException.class, () -> EtlManagedFtp.path(path, false));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedFtp.root("ftp://127.0.0.1:19086/", Set.of()));
        String address = "ftp://user:test-only@127.0.0.1:19086/";
        var failure = assertThrows(IllegalArgumentException.class, () -> EtlManagedFtp.root(address, Set.of(address)));
        assertFalse(failure.toString().contains("test-only"));
        assertEquals("127.0.0.1", EtlManagedFtp.root("ftp://127.0.0.1:19086/", Set.of("ftp://127.0.0.1:19086/")).getHost());
    }
}
