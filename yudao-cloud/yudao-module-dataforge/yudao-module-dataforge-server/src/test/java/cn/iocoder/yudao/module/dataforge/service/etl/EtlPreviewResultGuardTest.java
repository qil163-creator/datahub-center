package cn.iocoder.yudao.module.dataforge.service.etl;
import cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class EtlPreviewResultGuardTest {
    @Test void currentMaskAndRowChangesRejectCachedTransformedResultsAndRawEvidenceIsNeverReturned() {
        var guard = new EtlPreviewResultGuard(); AtomicBoolean mask = new AtomicBoolean();
        guard.currentAccessCheck(() -> {});
        var policy = guard.wrap(new EtlReadPolicyPort() {
            public String rewrite(String node, Long database, String sql) { return sql; }
            public List<Map<String, Object>> filter(String node, Long database, String sql, List<Map<String, Object>> rows) {
                return mask.get() ? List.of(Map.of("secret", "***")) : rows;
            }
        });
        var safe = policy.filter("source", 1L, "SELECT * FROM test", List.of(Map.of("secret", "fixture")), "SELECT * FROM test");
        assertEquals("fixture", safe.getFirst().get("secret")); assertDoesNotThrow(guard::verify);
        mask.set(true); assertThrows(IllegalStateException.class, guard::verify);
        guard.clear(); assertThrows(IllegalStateException.class, guard::verify);
    }
    @Test void changedRewriteAndRevokedGovernanceAccessFailClosed() {
        var guard = new EtlPreviewResultGuard(); AtomicBoolean change = new AtomicBoolean(); guard.currentAccessCheck(() -> {});
        var policy = guard.wrap(new EtlReadPolicyPort() {
            public String rewrite(String node, Long database, String sql) { return change.get() ? sql + " WHERE false" : sql; }
            public List<Map<String, Object>> filter(String node, Long database, String sql, List<Map<String, Object>> rows) { return rows; }
        });
        policy.filter("source", 1L, "SELECT * FROM test", List.of()); change.set(true);
        assertThrows(IllegalStateException.class, guard::verify);
        change.set(false); guard.currentAccessCheck(() -> { throw new SecurityException(); });
        assertThrows(SecurityException.class, guard::verify);
    }
}
