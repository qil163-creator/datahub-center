package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlRunParameterResolverTest {

    @Test
    void appliesDefaultsAndPreservesLargeIntegerAndDecimalText() {
        var definitions = JSONUtil.parseArray("""
                [{"name":"id","type":"INTEGER","required":true,"defaultValue":"9007199254740993"},
                 {"name":"amount","type":"DECIMAL","required":false}]
                """);
        var values = JSONUtil.parseObj("""
                {"amount":"12345678901234567890.12345678901234567890"}
                """);
        assertEquals(Map.of("id", "9007199254740993", "amount", "12345678901234567890.12345678901234567890"),
                EtlRunParameterResolver.resolve(definitions, values));
    }

    @Test
    void rejectsUnknownNamesAndRecursiveMacroValues() {
        var definitions = JSONUtil.parseArray("[{\"name\":\"id\",\"type\":\"STRING\"}]");
        assertThrows(IllegalArgumentException.class, () -> EtlRunParameterResolver.resolve(definitions,
                JSONUtil.parseObj("{\"unknown\":\"x\"}")));
        assertThrows(IllegalArgumentException.class, () -> EtlRunParameterResolver.resolve(definitions,
                JSONUtil.parseObj("{\"id\":\"${params.other}\"}")));
    }
}
