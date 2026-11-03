package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ElasticsearchDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch.ElasticsearchWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.elasticsearch.ElasticsearchDatabaseSyncImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ElasticsearchNativeSafetyTest {
    private final ObjectMapper json = new ObjectMapper();
    private DataSource source;
    private Connection connection;
    private ElasticsearchNativeSession session;
    private MockedStatic<ElasticsearchNativeSession> nativeOpen;
    private final List<String> fields = List.of("_id", "value", "note");
    @BeforeEach void setup() throws Exception {
        source = mock(DataSource.class); connection = mock(Connection.class); session = mock(ElasticsearchNativeSession.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        nativeOpen = mockStatic(ElasticsearchNativeSession.class, CALLS_REAL_METHODS);
        nativeOpen.when(() -> ElasticsearchNativeSession.open(any(), anyLong())).thenReturn(session);
        nativeOpen.when(() -> ElasticsearchNativeSession.open(any(), anyLong(), anyInt())).thenReturn(session);
        when(session.request(eq("POST"),eq("/sample/_count"),anyString())).thenReturn(response(200,"{\"count\":0,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        when(session.request("POST","/sample/_refresh",null)).thenReturn(response(200,"{\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        when(session.request(eq("GET"), eq("/sample/_mapping"), isNull())).thenReturn(response(200,
                "{\"sample\":{\"mappings\":{\"properties\":{\"value\":{\"type\":\"long\"},\"note\":{\"type\":\"keyword\"}}}}}"));
    }
    @AfterEach void cleanup() { nativeOpen.close(); }

    @Test void failedReprepareCannotUseOldTargetAndBadLaterRowNeverSendsBulk() throws Exception {
        var writer = prepared();
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields, List.of(
                new Object[]{"a", 1L, "first"}, new Object[]{"b", new BigDecimal("2.1"), "bad"})));
        verify(session, never()).request(eq("POST"), startsWith("/_bulk"), anyString());
        assertThrows(IllegalArgumentException.class, () -> writer.prepareWrite("es", "unsafe*", fields));
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields, List.<Object[]>of(new Object[]{"a", 1L, "bad"})));
    }

    @Test void completePartialResponseHasCountsOnlyAfterConnectionClose() throws Exception {
        var writer = prepared();
        when(session.request(eq("POST"), startsWith("/_bulk"), anyString())).thenReturn(response(200,
                "{\"errors\":true,\"items\":[{\"create\":{\"_index\":\"sample\",\"_id\":\"a\",\"status\":201}},{\"create\":{\"_index\":\"sample\",\"_id\":\"b\",\"status\":400,\"error\":{\"reason\":\"secret value\"}}}]}"));
        var failure = assertThrows(PartialBatchWriteException.class, () -> writer.write(fields, rows()));
        assertEquals(1, failure.acknowledgedRows()); assertEquals(1, failure.rejectedRows()); assertEquals(0, failure.unknownRows());
        assertTrue(failure.terminationConfirmed()); assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("secret"));
        verify(connection, times(2)).close();
        verify(session, times(1)).request(eq("POST"), contains("refresh=wait_for"), anyString());
    }

    @Test void closeFailureDoesNotClaimConfirmedTerminationAfterAcknowledgedItems() throws Exception {
        var writer = prepared();
        when(session.request(eq("POST"), startsWith("/_bulk"), anyString())).thenReturn(response(200,
                "{\"errors\":false,\"items\":[{\"create\":{\"_index\":\"sample\",\"_id\":\"a\",\"status\":201}},{\"create\":{\"_index\":\"sample\",\"_id\":\"b\",\"status\":201}}]}"));
        doThrow(new SQLException("private endpoint")).when(connection).close();
        var failure = assertThrows(PartialBatchWriteException.class, () -> writer.write(fields, rows()));
        assertEquals(2, failure.acknowledgedRows()); assertEquals(0, failure.unknownRows()); assertFalse(failure.terminationConfirmed());
    }

    @Test void incompleteOrWrongDocumentReceiptIsUnknownAndNeverRetried() throws Exception {
        var writer = prepared();
        for (String body : List.of("{\"errors\":false,\"items\":[]}",
                "{\"errors\":false,\"items\":[{\"create\":{\"_index\":\"sample\",\"_id\":\"b\",\"status\":201}},{\"create\":{\"_index\":\"sample\",\"_id\":\"a\",\"status\":201}}]}")) {
            clearInvocations(session);
            when(session.request(eq("POST"), startsWith("/_bulk"), anyString())).thenReturn(response(200, body));
            assertThrows(UnconfirmedWriteException.class, () -> writer.write(fields, rows()));
            verify(session, times(1)).request(eq("POST"), startsWith("/_bulk"), anyString());
        }
    }

    @Test void escapedCommandBytesAreLimitedBeforeWrite() throws Exception {
        var writer = prepared(); writer.configureBatchLimits(new BatchLimits(10, 300, 200, 100, 1000));
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields,
                List.<Object[]>of(new Object[]{"a", 1L, "\u0001".repeat(80)})));
        verify(session, never()).request(eq("POST"), startsWith("/_bulk"), anyString());
    }

    @Test void nativeDiffRequiresIdentityAndResetsFailedPreparation() throws Exception {
        var sync = new ElasticsearchDatabaseSyncImpl(source);
        sync.prepare("es", "sample", fields, List.of("_id"));
        assertThrows(IllegalArgumentException.class, () -> sync.executeUpdate(List.of(
                new Object[]{"a", 1L, "first"}, new Object[]{"a", 2L, "duplicate"})));
        assertThrows(IllegalArgumentException.class, () -> sync.prepare("es", "sample", fields, List.of("value")));
        assertThrows(IllegalStateException.class, () -> sync.executeDelete(rows()));
        verify(session, never()).request(eq("POST"), startsWith("/_bulk"), anyString());
    }

    @Test void nativeDiffUpdateOnlySendsPreparedFieldsAndNeverEnablesUpsert() throws Exception {
        var sync = new ElasticsearchDatabaseSyncImpl(source);
        sync.prepare("es", "sample", List.of("_id", "value"), List.of("_id"));
        when(session.request(eq("POST"), startsWith("/_bulk"), anyString())).thenReturn(response(200,
                "{\"errors\":false,\"items\":[{\"update\":{\"_index\":\"sample\",\"_id\":\"a\",\"status\":200}}]}"));
        assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{"a", 9007199254740993L})));
        verify(session).request(eq("POST"), startsWith("/_bulk"), argThat(body -> {
            try {
                var lines = body.split("\n"); var header = json.readTree(lines[0]); var document = json.readTree(lines[1]);
                return header.path("update").path("_id").textValue().equals("a")
                        && document.path("doc").size() == 1 && document.path("doc").path("value").longValue() == 9007199254740993L
                        && !document.path("doc_as_upsert").booleanValue();
            } catch (Exception invalid) { return false; }
        }));
    }

    @Test void missingDeleteReceiptIsExplicitRejectionRatherThanFalseAcknowledgement() throws Exception {
        var sync = new ElasticsearchDatabaseSyncImpl(source);
        sync.prepare("es", "sample", List.of("_id"), List.of("_id"));
        when(session.request(eq("POST"), startsWith("/_bulk"), anyString())).thenReturn(response(200,
                "{\"errors\":false,\"items\":[{\"delete\":{\"_index\":\"sample\",\"_id\":\"a\",\"status\":404,\"result\":\"not_found\"}}]}"));
        var failure = assertThrows(PartialBatchWriteException.class,
                () -> sync.executeDelete(List.<Object[]>of(new Object[]{"a"})));
        assertEquals(0, failure.acknowledgedRows()); assertEquals(1, failure.rejectedRows());
        assertTrue(failure.terminationConfirmed());
    }

    @Test void transportReadOrResponseCloseFailureAfterSubmissionIsUnknownAndNotReplayed() throws Exception {
        var writer = prepared();
        when(session.request(eq("POST"), startsWith("/_bulk"), anyString()))
                .thenThrow(new SQLException("private payload from response close"));
        var failure = assertThrows(UnconfirmedWriteException.class, () -> writer.write(fields, rows()));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private"));
        verify(session, times(1)).request(eq("POST"), startsWith("/_bulk"), anyString());
        verify(connection, times(2)).close();
    }

    @Test void scrollAppliesProjectionOrderAndFetchSizeAndClearsOnEarlyClose() throws Exception {
        when(session.request(eq("POST"), eq("/sample/_search?scroll=2m"), anyString())).thenReturn(response(200,
                "{\"_scroll_id\":\"first\",\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[{\"_id\":\"a\",\"_source\":{\"value\":9007199254740993,\"note\":null}}]}}"));
        when(session.request(eq("DELETE"), eq("/_search/scroll"), anyString())).thenReturn(response(200,"{\"succeeded\":true}"));
        var reader = new ElasticsearchDatabaseOperator(source); reader.setFetchSize(2);
        try (var result = reader.queryTableData("es", "sample", List.of("note", "value", "_id"), List.of("value"))) {
            var rs = result.getResultset(); assertTrue(rs.next()); assertNull(rs.getObject(1)); assertTrue(rs.wasNull());
            assertEquals(9007199254740993L, rs.getObject("value")); assertEquals("a", rs.getObject(3));
        }
        verify(session).request(eq("POST"), eq("/sample/_search?scroll=2m"), argThat(body -> {
            try { var request = json.readTree(body); return request.path("size").asInt() == 2
                    && request.path("_source").toString().equals("[\"note\",\"value\"]")
                    && request.path("sort").get(0).path("value").asText().equals("asc"); }
            catch (Exception invalid) { return false; }
        }));
        verify(session).request(eq("DELETE"), eq("/_search/scroll"), contains("first"));
        verify(connection).close();
    }

    @Test void shardFailureClearsScrollAndReleasesConnectionRatherThanReturnPartialRows() throws Exception {
        when(session.request(eq("POST"), eq("/sample/_search?scroll=2m"), anyString())).thenReturn(response(200,
                "{\"_scroll_id\":\"partial\",\"timed_out\":false,\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}"));
        when(session.request(eq("DELETE"), eq("/_search/scroll"), anyString())).thenReturn(response(200,"{\"succeeded\":true}"));
        assertThrows(IllegalStateException.class, () -> new ElasticsearchDatabaseOperator(source).queryTableData("sample"));
        verify(session).request(eq("DELETE"), eq("/_search/scroll"), contains("partial")); verify(connection).close();
    }

    @Test void deleteByQueryPartialFailureIsUnknownAndNeverFallsBackToDeletingTheIndex() throws Exception {
        when(session.request(eq("POST"), contains("_delete_by_query"), anyString())).thenReturn(response(200,
                "{\"timed_out\":false,\"total\":2,\"deleted\":1,\"version_conflicts\":1,\"failures\":[]}"));
        assertThrows(UnconfirmedWriteException.class, () -> new ElasticsearchDatabaseOperator(source).truncateTableData("es", "sample"));
        verify(session, never()).request(eq("DELETE"), eq("/sample"), any());
    }
    private ElasticsearchWriterImpl prepared() { var writer = new ElasticsearchWriterImpl(source); writer.prepareWrite("es", "sample", fields); return writer; }
    private List<Object[]> rows() { return List.of(new Object[]{"a", 1L, "first"}, new Object[]{"b", 2L, "second"}); }
    private ElasticsearchNativeSession.Response response(int status, String body) throws Exception { return new ElasticsearchNativeSession.Response(status, json.readTree(body)); }
}
