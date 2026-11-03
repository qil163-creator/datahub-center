package cn.iocoder.yudao.framework.dbswitch.core;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresTransactionRouteTest {
    @ParameterizedTest @ValueSource(ints={1,2})
    void extractionPreservesAlreadyPersistedV1WriteFingerprint(int offset) throws Exception {
        String[] parts = {"fixture", "16384", "test_role", "test_role", "127.0.0.1", "5432"};
        var rows = mock(ResultSet.class);
        for (int i = 0; i < parts.length; i++) when(rows.getString(i + offset)).thenReturn(parts[i]);
        assertEquals(DigestUtil.sha256Hex("PG_WRITE_ROUTE_V1\0" + String.join("\0",parts)),
                PostgresTransactionRoute.fingerprint(rows,offset));
    }
    @ParameterizedTest @ValueSource(strings={"null","blank","long"})
    void missingOrOversizedRouteIsNotHashedAsValid(String mode) throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.getString(anyInt())).thenReturn(switch(mode) {case "null" -> null; case "blank" -> " "; default -> "a".repeat(1025);});
        var failure = assertThrows(SQLException.class, () -> PostgresTransactionRoute.fingerprint(rows,1));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("aaaa"));
    }
}
