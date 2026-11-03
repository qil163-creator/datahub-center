package cn.iocoder.yudao.framework.dbswitch.product.mongodb;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMongoDBImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import static org.mockito.Mockito.*;
class MongoConnectionDiagnosticTest {
    @Test void cdcReadsReplicaTopologyAndEffectiveChangeStreamPrivileges() throws Exception {
        var connection=mock(Connection.class);var session=mock(MongoNativeSession.class);var bytes=new byte[]{1};
        when(connection.getCatalog()).thenReturn("events");
        try(var sessions=mockStatic(MongoNativeSession.class);var replies=mockStatic(MongoReplies.class)) {
            sessions.when(()->MongoNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(bytes);when(session.command(eq("admin"),any())).thenReturn(bytes);
            replies.when(()->MongoReplies.success(session,bytes)).thenReturn(new ObjectMapper().readTree(
                    "{\"setName\":\"replica\",\"authInfo\":{\"authenticatedUserPrivileges\":[{\"resource\":{\"db\":\"events\",\"collection\":\"\"},\"actions\":[\"find\",\"changeStream\"]}]}}"));
            var result=new DatabaseMongoDBImpl().probeCdcReadiness(connection,3);
            org.junit.jupiter.api.Assertions.assertTrue(result.logConfigurationReady());
            org.junit.jupiter.api.Assertions.assertTrue(result.accountPrivilegeReady());
            verify(session).encode("{\"hello\":1,\"maxTimeMS\":3000}");
            verify(session).encode("{\"connectionStatus\":1,\"showPrivileges\":true,\"maxTimeMS\":3000}");
            verify(connection,never()).close();
        }
    }

    @Test void standaloneOrOtherDatabasePrivilegeNeverClaimsCdcReady() throws Exception {
        var connection=mock(Connection.class);var session=mock(MongoNativeSession.class);var bytes=new byte[]{1};
        when(connection.getCatalog()).thenReturn("events");
        try(var sessions=mockStatic(MongoNativeSession.class);var replies=mockStatic(MongoReplies.class)) {
            sessions.when(()->MongoNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(bytes);when(session.command(eq("admin"),any())).thenReturn(bytes);
            replies.when(()->MongoReplies.success(session,bytes)).thenReturn(new ObjectMapper().readTree(
                    "{\"authInfo\":{\"authenticatedUserPrivileges\":[{\"resource\":{\"db\":\"other\",\"collection\":\"\"},\"actions\":[\"find\",\"changeStream\"]}]}}"));
            var result=new DatabaseMongoDBImpl().probeCdcReadiness(connection,3);
            org.junit.jupiter.api.Assertions.assertFalse(result.logConfigurationReady());
            org.junit.jupiter.api.Assertions.assertFalse(result.accountPrivilegeReady());
        }
    }

    @Test void accountInspectsAuthenticatedUsersAndRolesWithoutExposingNames() throws Exception {
        var connection=mock(Connection.class);var session=mock(MongoNativeSession.class);var bytes=new byte[]{1};
        try(var sessions=mockStatic(MongoNativeSession.class);var replies=mockStatic(MongoReplies.class)) {
            sessions.when(()->MongoNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(bytes);when(session.command(anyString(),any())).thenReturn(bytes);
            replies.when(()->MongoReplies.success(session,bytes)).thenReturn(new ObjectMapper().readTree(
                    "{\"databases\":[{\"name\":\"events\"}],\"cursor\":{\"firstBatch\":[],\"id\":0},\"authInfo\":{\"authenticatedUsers\":[{\"user\":\"user\",\"db\":\"admin\"}],\"authenticatedUserRoles\":[]}}"));
            new DatabaseMongoDBImpl().probeAccountPrivileges(connection,"events",3);
            verify(session).encode("{\"connectionStatus\":1,\"maxTimeMS\":3000}");
            verify(connection,never()).close();
        }
    }

    @Test void namespaceUsesBoundedAuthorizedCollectionMetadataNotPing() throws Exception {
        var connection=mock(Connection.class); var session=mock(MongoNativeSession.class); var bytes=new byte[]{1};
        try(var sessions=mockStatic(MongoNativeSession.class);var replies=mockStatic(MongoReplies.class)) {
            sessions.when(()->MongoNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(bytes); when(session.command(anyString(),any())).thenReturn(bytes);
            replies.when(()->MongoReplies.success(session,bytes)).thenReturn(new ObjectMapper().readTree("{\"databases\":[{\"name\":\"events\"}],\"cursor\":{\"firstBatch\":[],\"id\":{\"$numberLong\":\"0\"}}}"));
            new DatabaseMongoDBImpl().probeNamespace(connection,"events",3);
            verify(session).encode("{\"listCollections\":1,\"nameOnly\":true,\"authorizedCollections\":true,\"filter\":{\"name\":\"__dbswitch_diagnostic_no_collection__\"},\"cursor\":{\"batchSize\":1},\"maxTimeMS\":3000}");
            verify(connection,never()).close();
        }
    }
}
