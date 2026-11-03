package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb.MongoWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoCellCodec;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native protocol boundary failures; BSON semantics themselves are exercised against real MongoDB. */
class MongoWriterSafetyTest {
    @Test void preparationOnlyObservesExistingIdentityAndNeverMutatesDataIndexesOrCollection() throws Exception {
        try (var fixture = new NativeFixture()) {
            fixture.writer.prepareWrite("archive", "documents", List.of("value"));
            verifyNoInteractions(fixture.session); verify(fixture.connection, never()).createStatement();
            verify(fixture.connection, never()).commit(); verify(fixture.connection, times(2)).close();
        }
    }
    @Test void malformedLaterRowRejectsWholeBatchBeforeObtainingAWriteConnection() throws Exception {
        try (var fixture = new NativeFixture()) {
            clearInvocations(fixture.source);
            assertThrows(IllegalArgumentException.class, () -> fixture.writer.write(List.of("value"),
                    List.of(new Object[]{"first"}, new Object[]{"second", "extra"})));
            verifyNoInteractions(fixture.source); verifyNoInteractions(fixture.session);
        }
    }
    @Test void invalidReprepareClearsTheOldTargetAndUnsafeFieldNamesCannotReachDriver() throws Exception {
        try (var fixture = new NativeFixture()) {
            clearInvocations(fixture.source);
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.writer.prepareWrite("archive", "documents');drop()", List.of("value")));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.writer.write(List.of("value"), List.<Object[]>of(new Object[]{"wrong target"})));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.writer.prepareWrite("archive", "documents", List.of("path.to.value")));
            verifyNoInteractions(fixture.source);
        }
    }
    @Test void physicalGenerationChangeIsRejectedBeforeAnyNativeCommand() throws Exception {
        try (var fixture = new NativeFixture()) {
            fixture.metadata.when(() -> MongoNativeMetadata.ordinaryCollection(fixture.connection, "archive", "documents"))
                    .thenReturn(JSONUtil.parseObj("{\"name\":\"documents\",\"type\":\"collection\",\"options\":{},\"info\":{\"uuid\":{\"changed\":true}}}"));
            assertThrows(IllegalStateException.class, () -> fixture.writer.write(List.of("value"), fixture.rows()));
            verifyNoInteractions(fixture.session);
        }
    }
    @Test void partialNativeReceiptReportsExactCountsAndCannotExposePayloadOrReplay() throws Exception {
        try (var fixture = new NativeFixture()) {
            fixture.receipt("{\"ok\":1,\"n\":1,\"writeErrors\":[{\"index\":0,\"code\":11000,\"errmsg\":\"secret payload\"}]}");
            var result = assertThrows(PartialBatchWriteException.class, () -> fixture.writer.write(List.of("value"), fixture.rows()));
            assertEquals(1, result.acknowledgedRows()); assertEquals(1, result.rejectedRows()); assertEquals(0, result.unknownRows());
            assertTrue(result.terminationConfirmed()); assertNull(result.getCause()); assertFalse(result.getMessage().contains("secret"));
            verify(fixture.session, times(1)).command(eq("archive"), any(byte[].class));
        }
    }
    @Test void truncatedReceiptOrWriteConcernFailureCannotBecomeSuccessOrAuthorizeReplay() throws Exception {
        try (var fixture = new NativeFixture()) {
            for (String receipt : List.of("{\"ok\":1,\"n\":1}",
                    "{\"ok\":1,\"n\":2,\"writeConcernError\":{\"code\":64}}",
                    "{\"ok\":1,\"n\":1,\"writeErrors\":[{\"index\":3,\"code\":11000}]}")) {
                fixture.receipt(receipt); clearInvocations(fixture.session);
                assertThrows(UnconfirmedWriteException.class, () -> fixture.writer.write(List.of("value"), fixture.rows()));
                verify(fixture.session, times(1)).command(eq("archive"), any(byte[].class));
            }
        }
    }
    @Test void failedConnectionCloseRetainsAcknowledgementButCannotClaimTermination() throws Exception {
        try (var fixture = new NativeFixture()) {
            fixture.receipt("{\"ok\":1,\"n\":2}");
            doThrow(new SQLException("private connection detail")).when(fixture.connection).close();
            var failure = assertThrows(PartialBatchWriteException.class, () -> fixture.writer.write(List.of("value"), fixture.rows()));
            assertEquals(2, failure.acknowledgedRows()); assertEquals(0, failure.unknownRows()); assertFalse(failure.terminationConfirmed());
        }
    }
    @Test void encodedBsonFramingOverheadIsLimitedBeforeSubmission() throws Exception {
        try (var fixture = new NativeFixture()) {
            fixture.writer.configureBatchLimits(new BatchLimits(10, 128, 128, 100, 1000));
            when(fixture.session.document(anyMap())).thenReturn(new byte[129]);
            assertThrows(IllegalArgumentException.class, () -> fixture.writer.write(List.of("value"), List.<Object[]>of(new Object[]{"small"})));
            verify(fixture.session, never()).command(anyString(), any(byte[].class));
        }
    }
    private static final class NativeFixture implements AutoCloseable {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final MongoNativeSession session = mock(MongoNativeSession.class);
        final MockedStatic<MongoNativeMetadata> metadata;
        final MockedStatic<MongoNativeSession> open;
        final MockedStatic<MongoCellCodec> codec;
        final MongoWriterImpl writer;
        NativeFixture() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            metadata = mockStatic(MongoNativeMetadata.class, CALLS_REAL_METHODS);
            metadata.when(() -> MongoNativeMetadata.ordinaryCollection(connection, "archive", "documents"))
                    .thenReturn(JSONUtil.parseObj("{\"name\":\"documents\",\"type\":\"collection\",\"options\":{},\"info\":{\"uuid\":{\"stable\":true}}}"));
            open = mockStatic(MongoNativeSession.class, CALLS_REAL_METHODS);
            open.when(() -> MongoNativeSession.open(any(), anyLong(), anyInt())).thenReturn(session);
            codec = mockStatic(MongoCellCodec.class);
            codec.when(() -> MongoCellCodec.encode(any(), any(), anyBoolean())).thenReturn(new byte[8]);
            when(session.document(anyMap())).thenReturn(new byte[8]);
            when(session.cells(any(byte[].class))).thenReturn(Map.of());
            when(session.array(anyList())).thenReturn(new byte[8]);
            when(session.encode(anyString())).thenReturn(new byte[8]);
            when(session.command(anyString(), any(byte[].class))).thenReturn(new byte[8]);
            writer = new MongoWriterImpl(source); writer.prepareWrite("archive", "documents", List.of("value"));
        }
        void receipt(String json) throws Exception { when(session.canonicalJson(any(byte[].class))).thenReturn(json); }
        List<Object[]> rows() { return List.of(new Object[]{"first"}, new Object[]{"second"}); }
        @Override public void close() { codec.close(); open.close(); metadata.close(); }
    }
}
