package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.model.DataxTaskParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataxCustomParametersTest {
    @Test void composesNativeJobWithEffectiveResourceLimitsAndNoCredentials() {
        var params = DataxTaskParameters.custom(reader(), writer(), runtime(0), List.of());
        var job = JSONUtil.parseObj((String) params.get("json")).getJSONObject("job");
        assertEquals(1, params.get("customConfig")); assertEquals(1, params.get("xms")); assertEquals(2, params.get("xmx"));
        var speed = job.getJSONObject("setting").getJSONObject("speed");
        assertEquals(2, speed.getInt("channel")); assertEquals(4096L, speed.getLong("byte")); assertEquals(10L, speed.getLong("record"));
        assertEquals(0, job.getJSONObject("setting").getJSONObject("errorLimit").getInt("record"));
        assertEquals("txtfilereader", job.getJSONArray("content").getJSONObject(0).getJSONObject("reader").getStr("name"));
        assertFalse(params.toString().contains("password"));
    }

    @Test void rejectsCredentialInjectionUnverifiedPluginsAndDsPlaceholders() {
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.custom(
                Map.of("name", "txtfilereader", "parameter", Map.of("password", "example")), writer(), runtime(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.custom(
                Map.of("name", "txtfilereader", "parameter", Map.of("path", "${sys.secret}")), writer(), runtime(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.custom(
                Map.of("name", "redisreader", "parameter", Map.of()), writer(), runtime(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.custom(reader(), writer(), runtime(1), List.of()));
    }

    private static DataxTaskParameters.RuntimeOptions runtime(int dirty) {
        return new DataxTaskParameters.RuntimeOptions(1024, 2048, 2, 4096, 10, dirty);
    }
    private static Map<String, Object> reader() {
        return Map.of("name", "txtfilereader", "parameter", Map.of("path", List.of("/tmp/qdata-csv/source.csv"),
                "column", List.of(Map.of("index", 0, "type", "long")), "fieldDelimiter", ",", "skipHeader", true));
    }
    private static Map<String, Object> writer() {
        return Map.of("name", "txtfilewriter", "parameter", Map.of("path", "/tmp/qdata-csv/out", "fileName", "data",
                "fileFormat", "csv", "fieldDelimiter", ",", "writeMode", "nonConflict"));
    }
}
