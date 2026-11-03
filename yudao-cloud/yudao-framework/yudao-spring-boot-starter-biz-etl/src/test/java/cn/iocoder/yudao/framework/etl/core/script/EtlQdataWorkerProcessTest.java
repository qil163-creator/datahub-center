package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 原站接口的每项验证都跨越真实隔离子 JVM；Docker 沙箱和原生引擎另行验收。 */
class EtlQdataWorkerProcessTest {
    private static String source(String body) {
        return "import com.alibaba.fastjson2.*; import tech.qiantong.qdata.common.core.base.EtlBase; public class QdataFixture implements EtlBase { public String execute(JSONObject data,Object logParams) throws Exception { " + body + " } }";
    }
    private static EtlScriptRunner runner() { return new EtlScriptRunner(EtlScriptWorkerPolicy.defaults(true)); }

    @Test void batchCanAggregateAcrossRowsRemoveAnInputAndAppendAnOutputWithoutNumericOrDateLoss() {
        String code = source("writeLog(logParams,\"bounded-discarded-log\"); JSONArray batch=data.getJSONArray(\"data\"); JSONObject first=batch.getJSONObject(0); first.put(\"amount\", first.getBigDecimal(\"amount\").add(batch.getJSONObject(1).getBigDecimal(\"amount\"))); batch.remove(1); JSONObject appended=new JSONObject(); appended.put(\"id\",9007199254740993L); appended.put(\"amount\",new java.math.BigDecimal(\"999999999999999999.1234\")); batch.add(appended); return \"1\";");
        var time = LocalDateTime.of(2026, 11, 2, 8, 9, 10, 123456789);
        try (var worker = runner().open(EtlScriptRunner.Language.QDATA_JAVA, code, () -> { }, () -> fail("终止必须确认"))) {
            assertNotEquals(ProcessHandle.current().pid(), worker.processId());
            var rows = worker.execute(List.of(Map.of("id", 9007199254740992L, "amount", new BigDecimal("123456789012345678.1234"), "time", time), Map.of("id", 2L, "amount", new BigDecimal("0.0001"))));
            assertEquals(2, rows.size()); assertEquals(9007199254740992L, rows.getFirst().get("id"));
            assertEquals(new BigDecimal("123456789012345678.1235"), rows.getFirst().get("amount")); assertEquals(time, rows.getFirst().get("time"));
            assertEquals(9007199254740993L, rows.get(1).get("id")); assertEquals(new BigDecimal("999999999999999999.1234"), rows.get(1).get("amount"));
        }
    }
    @Test void terminationAndMalformedBatchFailWithoutLeakingPrintedTokenOrSourceDiagnostics() {
        for (String code : List.of(source("writeLog(logParams,\"sensitive-qdata-token\"); return \"0\";"), source("data.put(\"data\", java.util.List.of(\"sensitive-qdata-token\")); return \"1\";"))) {
            var runner = runner();
            try (var worker = runner.open(EtlScriptRunner.Language.QDATA_JAVA, code, () -> { }, () -> fail("终止必须确认"))) {
                var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
                assertFalse(failure.getMessage().contains("sensitive-qdata-token")); assertNull(failure.getCause());
            }
            assertEquals(0, runner.activeWorkers());
        }
    }
    @Test void tokenResultsStayInsideWorkerProtocolForTheTrustedBrokerAndInvalidTokensFail() {
        try (var worker = runner().open(EtlScriptRunner.Language.QDATA_TOKEN, source("return \"fixture-token-汉字\";"), () -> { }, () -> fail("终止必须确认"))) {
            assertEquals(Map.of("__token", "fixture-token-汉字"), worker.execute(List.of(Map.of())).getFirst());
        }
        try (var worker = runner().open(EtlScriptRunner.Language.QDATA_TOKEN, source("return \"0\";"), () -> { }, () -> fail("终止必须确认"))) {
            assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
        }
    }
    @Test void parentJvmCannotCompileOrLoadTheLegacyUserClass() {
        assertThrows(IllegalStateException.class, () -> EtlJavaCompiler.compileQdataScript(source("return \"1\";")));
    }
}
