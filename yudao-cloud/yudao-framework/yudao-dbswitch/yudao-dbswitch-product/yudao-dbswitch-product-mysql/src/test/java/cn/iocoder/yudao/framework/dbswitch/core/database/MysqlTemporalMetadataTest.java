package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlTemporalMetadataTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothTableReadersRepairScaleWithoutManufacturingSchemaEvidence(boolean fullMetadata) throws Exception {
        var probe = new Probe(new DatabaseMysqlImpl());
        var evidence = probe.timestamp.getMetadataEvidence();
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenReturn(Map.of("seen", nativeColumn("datetime", 6)));
            var columns = probe.read(fullMetadata);
            assertEquals(6, columns.getFirst().getScaleSize());
            assertSame(evidence, columns.getFirst().getMetadataEvidence());
            assertEquals("`seen`  DATETIME(6)", probe.database.getFieldDefinition(new ColumnMetaData(columns.getFirst()),
                    List.of(), false, false, false).trim());
            catalog.verify(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30));
            verify(probe.connection, never()).setCatalog(anyString());
            verify(probe.connection, never()).commit();
            verify(probe.connection, never()).close();
        }
    }

    @Test
    void missingAndReorderedCatalogColumnsAreNotAcceptedAsZeroPrecision() throws Exception {
        var probe = new Probe(new DatabaseMysqlImpl());
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenReturn(Map.of());
            assertThrows(IllegalStateException.class, () -> probe.read(false));
            var unexpected = new LinkedHashMap<String, MysqlCursorMetadata.Column>();
            unexpected.put("other", nativeColumn("datetime", 6));
            unexpected.put("seen", nativeColumn("datetime", 6));
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenReturn(unexpected);
            assertThrows(IllegalStateException.class, () -> probe.read(false));
        }
    }

    @Test
    void duplicateJdbcColumnsFailBeforeReturningAmbiguousMetadata() throws Exception {
        var probe = new Probe(new DatabaseMysqlImpl());
        doReturn(List.of(probe.timestamp, probe.timestamp)).when(probe.database)
                .querySelectSqlColumnMeta(eq(probe.connection), anyString());
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenReturn(Map.of("seen", nativeColumn("datetime", 6)));
            assertThrows(IllegalStateException.class, () -> probe.read(false));
        }
    }

    @Test
    void absentOutOfRangeAndInconsistentNativePrecisionFail() throws Exception {
        var probe = new Probe(new DatabaseMysqlImpl());
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            for (Integer precision : new Integer[]{null, -1, 7}) {
                catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                        .thenReturn(Map.of("seen", nativeColumn("datetime", precision)));
                assertThrows(IllegalStateException.class, () -> probe.read(false));
            }
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenReturn(Map.of("seen", nativeColumn("time", 3)));
            assertThrows(IllegalStateException.class, () -> probe.read(false));
        }
    }

    @Test
    void catalogPermissionFailuresArePropagatedWithoutReturningDriverZero() throws Exception {
        var probe = new Probe(new DatabaseMysqlImpl());
        var failure = new SQLException("catalog permission unavailable", "42000");
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            catalog.when(() -> MysqlCursorMetadata.columns(probe.connection, "other_catalog", "events", 30))
                    .thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> probe.read(false)).getCause());
        }
    }

    @Test
    void nonTimestampTablesAndExplicitlyExcludedDelegatesDoNotReadMysqlCatalog() throws Exception {
        var ordinary = new Probe(new DatabaseMysqlImpl());
        ordinary.timestamp.setFieldType(Types.INTEGER);
        var delegate = new Probe(new DatabaseMysqlImpl() {
            @Override protected boolean supportsNativeTemporalMetadata() { return false; }
        });
        var otherProduct = new Probe(new DatabaseMysqlImpl() {
            @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.TIDB; }
        });
        try (var catalog = mockStatic(MysqlCursorMetadata.class)) {
            assertEquals(1, ordinary.read(false).size());
            assertEquals(1, delegate.read(false).size());
            assertEquals(1, otherProduct.read(false).size());
            catalog.verifyNoInteractions();
        }
    }

    private static MysqlCursorMetadata.Column nativeColumn(String type, Integer precision) {
        return new MysqlCursorMetadata.Column("seen", type, type, true,
                null, null, null, null, "", "", null, precision);
    }

    private static final class Probe {
        final DatabaseMysqlImpl database;
        final Connection connection = mock(Connection.class);
        final ColumnDescription timestamp = new ColumnDescription();

        Probe(DatabaseMysqlImpl database) throws SQLException {
            this.database = spy(database);
            timestamp.setFieldName("seen"); timestamp.setFieldType(Types.TIMESTAMP);
            timestamp.setFieldTypeName("DATETIME"); timestamp.setNullable(true); timestamp.setScaleSize(0);
            doReturn(List.of(timestamp)).when(this.database).querySelectSqlColumnMeta(eq(connection), anyString());
            var metadata = mock(DatabaseMetaData.class);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getSearchStringEscape()).thenReturn("\\");
            when(metadata.getColumns("other_catalog", null, "events", null)).thenReturn(mock(ResultSet.class));
        }

        List<ColumnDescription> read(boolean fullMetadata) {
            return fullMetadata ? database.queryTableColumnMeta(connection, "other_catalog", "events")
                    : database.queryTableColumnMetaOnly(connection, "other_catalog", "events");
        }
    }
}
