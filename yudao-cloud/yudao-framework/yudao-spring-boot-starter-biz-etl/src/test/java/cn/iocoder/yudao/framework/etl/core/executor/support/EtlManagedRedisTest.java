package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlManagedRedisTest {
    @Test void hashValuesPreserveExactIntegersAndMissingFieldsAndDeduplicateScanKeys() {
        var connection = connection("hash");
        when(connection.evalReadonly(any(byte[].class), anyList(), anyList())).thenReturn(Arrays.asList(bytes("9007199254740993"), bytes(""), null));
        var rows = new ArrayList<String>(); EtlManagedRedis.read(plan("hash"), connection, rows::add);
        assertEquals(1, rows.size()); var row = JSONUtil.parseObj(rows.getFirst());
        assertEquals("9007199254740993", row.getStr("id")); assertEquals("", row.getStr("empty")); assertTrue(JSONUtil.isNull(row.get("optional")));
        verify(connection, times(1)).evalReadonly(any(byte[].class), anyList(), anyList());
    }
    @Test void fractionalIntegersInvalidUtf8AndRowLimitsFailBeforeReturningSuccess() {
        var connection = connection("hash");
        when(connection.evalReadonly(any(byte[].class), anyList(), anyList())).thenReturn(List.of(bytes("1.25"), bytes(""), bytes("value")));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedRedis.read(plan("hash"), connection, value -> fail()));
        when(connection.evalReadonly(any(byte[].class), anyList(), anyList())).thenReturn(List.of(new byte[]{(byte) 0xc3, 0x28}, bytes(""), bytes("value")));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedRedis.read(plan("hash"), connection, value -> fail()));
        var list = connection("list"); when(list.llen("input:one")).thenReturn(2L);
        var plan = plan("list").set("maxRows", 1);
        assertThrows(IllegalArgumentException.class, () -> EtlManagedRedis.read(plan, list, value -> fail()));
        verify(list, never()).evalReadonly(any(byte[].class), anyList(), anyList());
    }
    @Test void keyTemplatesAndJsonOutputPreserveNullAndNeverPermitUnapprovedPrefixes() {
        var plan = plan("string").set("keyPattern", "result:${id}").set("sinkPrefixes", List.of("result:"));
        var row = new LinkedHashMap<String, String>(); row.put("id", "9007199254740993"); row.put("empty", ""); row.put("optional", null);
        var pipeline = mock(Pipeline.class); EtlManagedRedis.write(plan, pipeline, row);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pipeline).set(eq("result:9007199254740993"), payload.capture());
        assertTrue(payload.getValue().contains("\"optional\":null")); assertEquals("", JSONUtil.parseObj(payload.getValue()).getStr("empty"));
        plan.set("sinkPrefixes", List.of("foreign:")); assertThrows(IllegalArgumentException.class, () -> EtlManagedRedis.key(plan, row));
        plan.set("sinkPrefixes", List.of("result:")); row.put("id", null); assertThrows(IllegalArgumentException.class, () -> EtlManagedRedis.key(plan, row));
    }
    private static Jedis connection(String model) {
        var connection = mock(Jedis.class);
        when(connection.scan(any(byte[].class), any(ScanParams.class))).thenReturn(new ScanResult<>("0", List.of(bytes("input:one"), bytes("input:one"))));
        when(connection.type("input:one")).thenReturn(model); return connection;
    }
    private static JSONObject plan(String model) {
        var selectors = new LinkedHashMap<String, String>(); selectors.put("id", "id"); selectors.put("empty", "empty"); selectors.put("optional", "optional");
        return JSONUtil.createObj().set("dataType", model).set("keyPattern", "input:*").set("scanCount", 100).set("maxKeys", 10).set("maxRows", 10).set("maxBytes", 100000)
                .set("selectors", selectors)
                .set("resultTypes", Map.of("id", Map.of("sqlType", "BIGINT"), "empty", Map.of("sqlType", "STRING"), "optional", Map.of("sqlType", "STRING")));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
