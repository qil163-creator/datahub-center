package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionOnlyDiagnosisConverterTest {
    private static ConnectionDiagnosticResult.Check check(String key, String status) {
        return new ConnectionDiagnosticResult.Check("BASIC", key, "实际协议探测", status, 1, "安全诊断信息", null, null);
    }
    @Test void protocolEvidenceDoesNotInventMetadataOrWriteCapability() {
        var result = new ConnectionDiagnosticResult("PASS", true, 1, List.of(check("connection", "PASS")));
        var response = DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now());
        assertTrue(response.isConnectionSuccessful());
        assertEquals(1, response.getNormalCount()); assertEquals(1, response.getSkippedCount());
        assertEquals(2, response.getTotalCount());
        assertEquals("CONNECT_ONLY", response.getGroups().getFirst().getItems().getLast().getCheckCode());
        assertWireItems(response);
    }
    @Test void inconsistentOrMissingResultsNeverBecomeOnline() {
        for (var result : List.of(
                new ConnectionDiagnosticResult("PASS", true, 1, List.of(check("connection", "FAIL"))),
                new ConnectionDiagnosticResult("PASS", true, 1, List.of(check("CONFIG", "PASS"))),
                new ConnectionDiagnosticResult("PASS", true, 1, List.of(check("connection", null))),
                new ConnectionDiagnosticResult("PASS", true, 1, List.of(check("connection", "PASS"), check("connection", "PASS"))))) {
            assertFalse(DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now()).isConnectionSuccessful());
        }
        var missing = DatabaseDiagnosisConverter.convertConnectionOnly(null, LocalDateTime.now());
        assertFalse(missing.isConnectionSuccessful());
        assertWireItems(missing);
    }
    @Test void optionalMetadataFailureDoesNotEraseVerifiedProtocolAndTargetAccess() {
        var result = new ConnectionDiagnosticResult("FAIL", true, 3, List.of(check("AUTH", "PASS"),
                check("SCHEMA", "PASS"), check("TABLES", "FAIL")));
        var response = DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now());
        assertTrue(response.isConnectionSuccessful());
        assertEquals("FAIL", response.getOverallStatus());
        assertEquals(1, response.getErrorCount());
        assertWireItems(response);
    }
    @Test void targetFailureVetoesAnInconsistentProtocolSuccessFlag() {
        var result = new ConnectionDiagnosticResult("FAIL", true, 2,
                List.of(check("AUTH", "PASS"), check("SCHEMA", "FAIL")));
        assertFalse(DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now()).isConnectionSuccessful());
    }
    @Test void missingDriversRemainUnverified() {
        var result = new ConnectionDiagnosticResult("SKIPPED", false, 1, List.of(check("connection", "SKIPPED")));
        var response = DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now());
        assertFalse(response.isConnectionSuccessful()); assertEquals("SKIPPED", response.getOverallStatus());
        assertEquals(0, response.getErrorCount());
        assertWireItems(response);
    }

    @Test void missingRequiredProbeTextRemainsUnverified() {
        var check = new ConnectionDiagnosticResult.Check("BASIC", "connection", null, "PASS", 1, null, null, null);
        var result = new ConnectionDiagnosticResult("PASS", true, 1, List.of(check));
        var response = DatabaseDiagnosisConverter.convertConnectionOnly(result, LocalDateTime.now());
        assertFalse(response.isConnectionSuccessful());
        assertEquals("PROBE_RESULT", response.getGroups().getFirst().getItems().getFirst().getCheckCode());
        assertWireItems(response);
    }

    private static void assertWireItems(cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO response) {
        var json = JsonUtils.getObjectMapper().readTree(JsonUtils.toJsonString(response));
        assertTrue(json.path("checkedAt").isNumber());
        for (var item : json.path("groups").get(0).path("items")) {
            for (var field : List.of("checkCode", "checkName", "checkContent", "status", "message", "impact")) {
                assertTrue(item.path(field).isString(), field);
            }
            assertTrue(item.path("durationMs").isNumber());
        }
    }
}
