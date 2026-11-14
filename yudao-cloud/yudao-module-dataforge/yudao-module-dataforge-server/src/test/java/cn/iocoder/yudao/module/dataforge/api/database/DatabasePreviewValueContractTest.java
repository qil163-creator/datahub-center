package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabasePreviewValueContractTest {
    private final DatabaseApiImpl api = new DatabaseApiImpl();

    @Test void preciseNumbersBecomeDecimalStringsBeforeBothJsonEncodersSeeThem() throws Exception {
        String amount = "12345678901234567890.12345678";
        String integer = "170141183460469231731687303715884105726";
        Object decimal = ReflectionTestUtils.invokeMethod(api, "normalizeValue", new BigDecimal(amount));
        Object large = ReflectionTestUtils.invokeMethod(api, "normalizeValue", new BigInteger(integer));
        assertEquals(amount, decimal); assertEquals(integer, large);
        assertEquals("1000000000000000000000", ReflectionTestUtils.invokeMethod(api,
                "normalizeValue", new BigDecimal("1E+21")));
        var response = new DatabasePreviewRespDTO();
        response.setRows(List.of(Map.of("amount", decimal, "wide_id", large)));
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        var v2 = jackson2.readTree(jackson2.writeValueAsBytes(response)).get("rows").get(0);
        var v3 = jackson3.readTree(jackson3.writeValueAsBytes(response)).get("rows").get(0);
        assertTrue(v2.get("amount").isTextual()); assertEquals(amount, v2.get("amount").asText());
        assertTrue(v3.get("wide_id").isString()); assertEquals(integer, v3.get("wide_id").asString());
    }

    @Test void booleansAcceptOnlyExplicitTrueFalseOrBinaryValues() {
        for (int type : new int[]{Types.BOOLEAN, Types.BIT}) {
            for (String value : List.of("true", "TRUE", " 1 ")) {
                assertSame(Boolean.TRUE, ReflectionTestUtils.invokeMethod(api, "convertFilterValue", value, type));
            }
            for (String value : List.of("false", "FALSE", " 0 ")) {
                assertSame(Boolean.FALSE, ReflectionTestUtils.invokeMethod(api, "convertFilterValue", value, type));
            }
            for (String value : List.of("yes", "", "2", "true or 1=1")) {
                assertThrows(ServiceException.class,
                        () -> ReflectionTestUtils.invokeMethod(api, "convertFilterValue", value, type));
            }
        }
    }
}
