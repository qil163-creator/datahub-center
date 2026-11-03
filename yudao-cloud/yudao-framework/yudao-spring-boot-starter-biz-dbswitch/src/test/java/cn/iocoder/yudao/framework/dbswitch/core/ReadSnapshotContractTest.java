package cn.iocoder.yudao.framework.dbswitch.core;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadSnapshotContractTest {
    @Test void existingSnapshotImplementationDoesNotInventCommitVisibility() throws Exception {
        ReadSnapshot legacy = new ReadSnapshot() {
            public Evidence evidence() { return new Evidence(1,"legacy","a".repeat(64)); }
            public Checkpoint checkpoint() { return () -> { }; }
            public void close() { }
        };
        assertThrows(SQLFeatureNotSupportedException.class, () -> legacy.inspectCommits(java.util.List.of()));
    }

    @Test void commitObservationJsonKeepsXidAsExactStringAndImmutableList() throws Exception {
        var receipt = new cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt(
                1,"POSTGRESQL_XID8_V1","18446744073709551615","b".repeat(64));
        var entries = new java.util.ArrayList<ReadSnapshot.CommitObservation>();
        entries.add(new ReadSnapshot.CommitObservation(receipt,"STATUS_UNAVAILABLE"));
        var value = new ReadSnapshot.CommitVisibility(1,new ReadSnapshot.Evidence(1,"snapshot","a".repeat(64)),entries);
        entries.clear(); assertEquals(1,value.observations().size());
        assertThrows(UnsupportedOperationException.class, () -> value.observations().clear());
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        String json2 = jackson2.writeValueAsString(value), json3 = jackson3.writeValueAsString(value);
        assertEquals(jackson2.readTree(json2),jackson2.readTree(json3));
        assertTrue(jackson2.readTree(json2).get("observations").get(0).get("receipt").get("transactionId").isTextual());
        assertEquals(value,jackson2.readValue(json3,ReadSnapshot.CommitVisibility.class));
        assertEquals(value,jackson3.readValue(json2,ReadSnapshot.CommitVisibility.class));
    }
    @ParameterizedTest @ValueSource(ints={-1,0,301,Integer.MAX_VALUE})
    void unboundedTimeoutIsRejectedWithoutTouchingConnection(int timeout) {
        var connection=mock(Connection.class);
        assertThrows(IllegalArgumentException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                .openReadSnapshot(connection,timeout));
        verifyNoInteractions(connection);
    }
    @Test void driverUnsupportedExceptionMustNotBecomeAnUnverifiedReadFallback() throws Exception {
        var connection=mock(Connection.class);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getMetaData()).thenThrow(new SQLFeatureNotSupportedException("fixture-private-driver"));
        var failure=assertThrows(SQLException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                .openReadSnapshot(connection,5));
        assertFalse(failure instanceof SQLFeatureNotSupportedException);assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
        verify(connection,never()).setAutoCommit(anyBoolean());verify(connection,never()).close();
    }
    @Test void mismatchedActualProductCannotBeTreatedAsPostgres() throws Exception {
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(connection.getAutoCommit()).thenReturn(true);when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("fixture-other");
        assertThrows(SQLException.class,()->cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(connection,5));
        verify(connection,never()).setReadOnly(anyBoolean());verify(connection,never()).rollback();
    }
    @Test void snapshotEvidenceHasTheSameMinimalJsonShapeWithJackson2And3() throws Exception {
        var value=new ReadSnapshot.Evidence(1,"POSTGRESQL_REPEATABLE_READ_V1","a".repeat(64));
        var jackson2=new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3=tools.jackson.databind.json.JsonMapper.builder().build();
        String oldJson=jackson2.writeValueAsString(value);String newJson=jackson3.writeValueAsString(value);
        assertEquals(jackson2.readTree(oldJson),jackson2.readTree(newJson));
        assertEquals(3,jackson2.readTree(oldJson).size());
        assertEquals(value,jackson2.readValue(newJson,ReadSnapshot.Evidence.class));
        assertEquals(value,jackson3.readValue(oldJson,ReadSnapshot.Evidence.class));
    }
}
