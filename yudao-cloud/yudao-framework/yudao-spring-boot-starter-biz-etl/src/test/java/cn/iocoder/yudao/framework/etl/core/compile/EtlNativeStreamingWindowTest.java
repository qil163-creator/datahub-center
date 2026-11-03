package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeStreamingWindowTest {
    static JSONObject window() {
        return new JSONObject().set("version", 1).set("kind", "TUMBLING").set("timeMode", "PROCESSING_TIME")
                .set("sizeMillis", 2000).set("maxRows", 1000).set("maxBytes", 1_048_576).set("emit", "ON_CLOSE").set("latePolicy", "PROCESS_ON_ARRIVAL");
    }
    static JSONObject config() {
        return new JSONObject().set("streamingWindow", window()).set("deduplicationStrategy", "KEY_FIELDS").set("keyFields", List.of("key"))
                .set("ignoreCaseFields", List.of("key")).set("uniqueFields", List.of("seq")).set("orderBy", List.of(Map.of("field", "seq", "order", "ASC")));
    }
    static LinkedHashMap<String, String> fields() {
        var fields = new LinkedHashMap<String, String>(); fields.put("key", "STRING"); fields.put("seq", "BIGINT"); fields.put("value", "DECIMAL(38,18)"); return fields;
    }
    @Test void windowContractMustBeExplicitAndFullyConsumed() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.require(new JSONObject()));
        for (JSONObject value : List.of(window().set("sizeMillis", "1000.5"), window().set("latePolicy", "REOPEN"), window().set("unused", true), window().set("maxRows", 10001)))
            assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.require(new JSONObject().set("streamingWindow", value)));
        assertEquals(2000, EtlNativeStreamingWindow.require(config()).sizeMillis());
    }
    @Test void dedupUsesBoundedNativeAggregateAndLosslessDeclarations() {
        String sql = EtlNativeStreamingWindow.deduplicate(config(), "SELECT * FROM source", fields());
        assertTrue(sql.contains("PROCTIME()")); assertTrue(sql.contains("INTERVAL '2.000' SECOND"));
        assertTrue(sql.contains("LOWER(`key`)")); assertTrue(sql.contains("DH_WINDOW_PICK"));
        assertTrue(sql.contains("1|F|1000|1048576|1:A:N|1"));
        assertTrue(sql.contains("CAST(`__dh_picked`[3] AS DECIMAL(38,18))"));
        assertFalse(sql.contains("ROW_NUMBER"));
    }
    @Test void reservedColumnsAndAmbiguousUniqueOrderingAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.deduplicate(config().set("uniqueFields", List.of("value")), "source", fields()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.deduplicate(config().set("ignoreCaseFields", List.of("seq")), "source", fields()));
        var fields = fields(); fields.put("window_start", "TIMESTAMP(3)");
        assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.deduplicate(config(), "source", fields));
    }
    @Test void complexAndBinaryFieldsCannotBeLossilyStringifiedIntoWindowState() {
        for (String type : List.of("ARRAY<STRING>", "MAP<STRING,STRING>", "ROW<x INT>", "BINARY", "VARBINARY(100)")) {
            var fields = fields(); fields.put("unsupported", type);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeStreamingWindow.deduplicate(config(), "source", fields), type);
        }
    }
}
