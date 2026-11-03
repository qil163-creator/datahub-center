package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoNativeDiagnosticTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void publicProbeReturnsNativeReadEvidenceWithoutReadingBusinessDocuments() throws Exception {
        var connection = mock(Connection.class);
        var session = mock(MongoNativeSession.class);
        var cursor = mock(MongoNativeSession.Cursor.class);
        List<String> commands = new ArrayList<>();
        when(session.encode(anyString())).thenAnswer(call -> call.<String>getArgument(0).getBytes());
        when(session.command(anyString(), any())).thenAnswer(call -> {
            String command = new String(call.<byte[]>getArgument(1)); commands.add(command); return command.getBytes();
        });
        when(session.find(eq("events"), eq("business"), any(), any(), any(), eq(1))).thenReturn(cursor);
        try (var sessions = mockStatic(MongoNativeSession.class); var replies = mockStatic(MongoReplies.class)) {
            sessions.when(() -> MongoNativeSession.open(connection, 262144, 5)).thenReturn(session);
            replies.when(() -> MongoReplies.success(eq(session), any())).thenAnswer(call -> {
                String command = new String(call.<byte[]>getArgument(1));
                if (command.contains("listDatabases")) return json.readTree("{\"databases\":[{\"name\":\"events\"}]}");
                if (command.contains("listCollections")) return json.readTree("{\"cursor\":{\"id\":0,\"firstBatch\":[{\"name\":\"business\",\"type\":\"collection\",\"options\":{\"validator\":{}}}]}}");
                if (command.contains("listIndexes")) return json.readTree("{\"cursor\":{\"id\":0,\"firstBatch\":[{\"key\":{\"_id\":1}}]}}");
                if (command.contains("connectionStatus")) return json.readTree("{\"authInfo\":{\"authenticatedUsers\":[{}],\"authenticatedUserRoles\":[],\"authenticatedUserPrivileges\":[]}}");
                return json.readTree("{\"ok\":1}");
            });
            replies.when(() -> MongoReplies.integer(any())).thenAnswer(call -> call.<com.fasterxml.jackson.databind.JsonNode>getArgument(0).asLong());
            var result = new MongoConnectionProbe().diagnose(request(Map.of()), () -> connection);
            assertTrue(result.connectionSuccessful());
            assertEquals("PASS", check(result, "TABLES").status());
            assertEquals("WARN", check(result, "COLUMNS").status());
            assertTrue(check(result, "COLUMNS").message().contains("未读取业务文档"));
            assertEquals("PASS", check(result, "ZERO_ROW_READ").status());
            assertEquals("WRITE_NOT_REQUESTED", check(result, "TEMP_CREATE").reasonCode());
            verify(cursor).next(); verify(cursor).close(); verify(connection).close();
            assertTrue(commands.stream().anyMatch(text -> text.contains("$exists") && text.contains("false")));
            assertFalse(commands.stream().anyMatch(text -> text.contains("\"limit\":0") || text.contains("\"create\":")));
        }
    }

    @Test void failedAuthenticationStillReturnsSafePerItemEvidence() throws Exception {
        var result = new MongoConnectionProbe().diagnose(request(Map.of()), () -> { throw new SQLException("secret credential endpoint"); });
        assertFalse(result.connectionSuccessful());
        assertEquals("FAIL", check(result, "AUTH").status());
        assertEquals("SKIPPED", check(result, "TABLES").status());
        assertFalse(result.toString().contains("secret credential"));
    }

    static ConnectionProbeRequest request(Map<String, String> properties) {
        return new ConnectionProbeRequest(ProductTypeEnum.MONGODB, null, null, null, "user", "synthetic", "events", null, properties, Duration.ofSeconds(5));
    }
    static ConnectionDiagnosticResult.Check check(ConnectionDiagnosticResult result, String key) {
        return result.checks().stream().filter(check -> key.equals(check.key())).findFirst().orElseThrow();
    }
}
