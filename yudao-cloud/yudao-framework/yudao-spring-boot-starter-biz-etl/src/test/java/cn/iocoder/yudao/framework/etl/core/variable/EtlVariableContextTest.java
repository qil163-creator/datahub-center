package cn.iocoder.yudao.framework.etl.core.variable;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlVariableContextTest {

    @Test
    void bindsOnlyStringLeavesAndEscapesValuesAsJsonStrings() {
        String injected = "x\",\n\"admin\":true,\"note\":\"\\\\tail";
        var variables = new EtlVariableContext(Map.of("value", injected, "large", "9007199254740993"));
        String resolved = variables.resolveJson("{\"${params.value}\":\"${params.value}\",\"n\":\"${params.large}\",\"flag\":true}");

        var parsed = JSONUtil.parseObj(resolved);
        assertEquals(injected, parsed.getStr("${params.value}"));
        assertEquals("9007199254740993", parsed.getStr("n"));
        assertTrue(parsed.getBool("flag"));
        assertEquals(3, parsed.size());
    }

    @Test
    void preservesEmbeddedQuoteBackslashAndNewlineWithoutChangingObjectShape() {
        String value = "quote=\" slash=\\ line\nnext";
        String output = new EtlVariableContext(Map.of("v", value))
                .resolveJson("{\"key\":\"prefix:${params.v}:suffix\",\"child\":{\"fixed\":1}}");
        var parsed = JSONUtil.parseObj(output);
        assertEquals("prefix:" + value + ":suffix", parsed.getStr("key"));
        assertEquals(1, parsed.getJSONObject("child").getInt("fixed"));
    }
}
