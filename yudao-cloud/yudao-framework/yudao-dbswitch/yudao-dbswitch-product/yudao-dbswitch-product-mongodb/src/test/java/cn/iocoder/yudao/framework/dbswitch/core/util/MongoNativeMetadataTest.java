package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMongoDBImpl;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoNativeMetadataTest {
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"a.b", "db;drop()", "db'", "db\"", " db", "db ", "$db", "db\u0000", "db-name", "客户"})
    void unsafeIdentifiersCannotReachJdbc(String name) {
        var connection=mock(Connection.class);
        assertThrows(IllegalArgumentException.class,()->MongoNativeMetadata.collection("safe",name));
        assertThrows(IllegalArgumentException.class,()->MongoNativeMetadata.ordinaryCollection(connection,name,"safe"));
        assertThrows(IllegalArgumentException.class,()->new DatabaseMongoDBImpl().getTableDDL(connection,"safe",name));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"sample", "admin.sample", "local.sample", "config.sample", "db.table.more", "db.table;drop()", "db.\"table\""})
    void fingerprintsRequireExplicitUserDatabaseAndCollection(String reference) {
        var connection=mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class,()->MongoNativeMetadata.fingerprint(connection,reference));
        verifyNoInteractions(connection);
    }

    @Test void explicitCollectionExpressionIsIndependentOfSessionVariables() {
        assertEquals("client.getDatabase('tenant_a').getCollection('orders')",MongoNativeMetadata.collection("tenant_a","orders"));
        assertThrows(IllegalArgumentException.class,()->MongoNativeMetadata.database("a".repeat(64)));
    }

    @Test void commandAppliesNativeBoundsWithoutShellEvaluationOrClosingCallerConnection() throws Exception {
        var connection=mock(Connection.class);var session=mock(MongoNativeSession.class);
        byte[] command=new byte[]{1},reply=new byte[]{2};
        try(var open=mockStatic(MongoNativeSession.class)) {
            open.when(()->MongoNativeSession.open(connection,131072,5)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(command); when(session.command("tenant_a",command)).thenReturn(reply);
            when(session.metadataJson(reply)).thenReturn("{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":[{\"name\":\"orders\",\"type\":\"collection\",\"options\":{}}]}}");
            assertEquals("orders",MongoNativeMetadata.ordinaryCollection(connection,"tenant_a","orders").getStr("name"));
            verify(session).encode("{listCollections:1,filter:{name:'orders'},nameOnly:false,cursor:{batchSize:2},maxTimeMS:5000}");
            verify(session).command("tenant_a",command); verify(connection,never()).createStatement();
            verify(connection,never()).close();verify(connection,never()).setCatalog(anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"garbage","{}","{\"ok\":0}","{\"ok\":1,\"cursor\":{\"id\":1,\"firstBatch\":[]}}",
        "{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":[]}}",
        "{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":[{\"name\":\"orders\",\"type\":\"view\",\"options\":{}}]}}"})
    void incompleteNativeMetadataCannotBecomeAnIdentity(String json) throws Exception {
        var connection=mock(Connection.class);var session=mock(MongoNativeSession.class);
        byte[] command=new byte[]{1},reply=new byte[]{2};
        try(var open=mockStatic(MongoNativeSession.class)) {
            open.when(()->MongoNativeSession.open(connection,131072,5)).thenReturn(session);
            when(session.encode(anyString())).thenReturn(command);when(session.command("tenant_a",command)).thenReturn(reply);
            when(session.metadataJson(reply)).thenReturn(json);
            assertThrows(SQLFeatureNotSupportedException.class,()->MongoNativeMetadata.ordinaryCollection(connection,"tenant_a","orders"));
            verify(connection,never()).createStatement();verify(connection,never()).close();
        }
    }
}
