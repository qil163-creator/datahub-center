package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeScriptStateTest {
    @Test void exactCheckpointCodecRejectsTruncationTrailingBytesVersionAndOversizedWindows() {
        var row = new LinkedHashMap<String, Object>(); row.put("id", 9007199254740993L); row.put("amount", new BigDecimal("12345678901234567890.1234"));
        row.put("time", LocalDateTime.of(2026, 11, 2, 1, 2, 3, 123456789)); row.put("null", null);
        var state = new EtlNativeScriptState.Snapshot("a".repeat(64), "stage-0", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, 123456789, List.of(row));
        byte[] bytes = EtlNativeScriptState.encode(state);
        assertEquals(state, EtlNativeScriptState.decode(bytes));
        assertEquals(Integer.BYTES + EtlNativeScriptState.rowBytes(row), EtlNativeScriptState.batchBytes(List.of(row)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptState.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptState.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        var future = bytes.clone(); future[3] = 2; assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptState.decode(future));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptState.rowBytes(Map.of("text", "x".repeat(EtlNativeScriptState.MAX_BATCH_BYTES))));
        assertThrows(IllegalArgumentException.class, () -> new EtlNativeScriptState.Snapshot(state.scope(), state.partition(), state.run(), "", 1, 0, List.of()));
    }
}
