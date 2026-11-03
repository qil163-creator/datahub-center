package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Observation alignment only; native catalog execution is covered by OscarNativeCatalogTest. */
class OscarNativeCatalogContractTest {
    @Test void provenancePreservesObservedLicenseTextWhileAdmissionUsesTheStableBuild() throws Exception {
        var c = connection(new int[]{1, 2}, new String[]{"A", "B"});
        String observed = OscarNativeCatalog.SERVER_VERSION.replace("180 days", "179 days");
        when(c.getMetaData().getDatabaseProductVersion()).thenReturn(observed);
        when(c.createStatement().executeQuery("SELECT VERSION() FROM DUAL").getString(1)).thenReturn(observed);
        var schema = OscarNativeCatalog.read(c, "OWNER", "TABLE_NAME", 5);
        assertEquals(observed, schema.provenance().serverVersion());
        assertEquals(observed, schema.columns().getFirst().getMetadataEvidence().provenance().serverVersion());
    }
    @Test void incompleteDuplicateDescendingOrMismatchedCatalogColumnsFailTheAdmittedRead() throws Exception {
        for (int[] ordinals : List.of(new int[]{1, 1}, new int[]{4, 1}, new int[]{0, 2})) {
            assertThrows(SQLException.class,
                    () -> OscarNativeCatalog.read(connection(ordinals, new String[]{"A", "B"}), "OWNER", "TABLE_NAME", 5));
        }
        assertThrows(SQLException.class,
                () -> OscarNativeCatalog.read(connection(new int[]{1, 2}, new String[]{"A", "OTHER"}), "OWNER", "TABLE_NAME", 5));
        var incomplete = connection(new int[]{1}, new String[]{"A"});
        assertThrows(SQLException.class, () -> OscarNativeCatalog.read(incomplete, "OWNER", "TABLE_NAME", 5));
        verify(incomplete, never()).commit();
        verify(incomplete, never()).rollback();
        verify(incomplete, never()).setAutoCommit(anyBoolean());
        verify(incomplete, never()).close();
    }

    @Test void verifiedInt8OidMatchesBigintShellWithoutRewritingNativeEvidence() throws Exception {
        var schema = OscarNativeCatalog.read(connection(new int[]{1, 3}, new String[]{"A", "B"},
                Types.BIGINT, "bigint", "INT8", 20), "OWNER", "TABLE_NAME", 5);
        assertEquals(List.of("A", "B"), schema.columns().stream().map(column -> column.getFieldName()).toList());
        for (var column : schema.columns()) {
            assertEquals(Types.BIGINT, column.getFieldType());
            assertEquals("bigint", column.getFieldTypeName());
            assertEquals("INT8", column.getMetadataEvidence().nativeType().value());
        }
    }

    @Test void unverifiedAliasOidOrJdbcTypeStillFailsTheAdmittedRead() throws Exception {
        record Observation(int jdbcType, String jdbcName, String nativeName, Integer nativeOid) { }
        for (var observation : List.of(
                new Observation(Types.BIGINT, "bigint", "INT8", 23),
                new Observation(Types.BIGINT, "bigint", "INT8", null),
                new Observation(Types.INTEGER, "bigint", "INT8", 20),
                new Observation(Types.BIGINT, "bigserial", "INT8", 20),
                new Observation(Types.BIGINT, "bigint", "INT4", 20))) {
            var connection = connection(new int[]{1, 3}, new String[]{"A", "B"}, observation.jdbcType(),
                    observation.jdbcName(), observation.nativeName(), observation.nativeOid());
            var failure = assertThrows(SQLException.class,
                    () -> OscarNativeCatalog.read(connection, "OWNER", "TABLE_NAME", 5));
            assertEquals("Oscar catalog native type disagrees with JDBC shell", failure.getMessage());
        }
    }

    private Connection connection(int[] ordinals, String[] catalogNames) throws Exception {
        return connection(ordinals, catalogNames, Types.INTEGER, "int4", "INT4", 23);
    }

    private Connection connection(int[] ordinals, String[] catalogNames, int jdbcType, String jdbcName,
                                  String nativeName, Integer nativeOid) throws Exception {
        var connection = mock(Connection.class);
        var jdbc = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(jdbc);
        when(jdbc.getDatabaseProductName()).thenReturn("OSCAR");
        when(jdbc.getDatabaseProductVersion()).thenReturn(OscarNativeCatalog.SERVER_VERSION);
        var version = mock(Statement.class);
        var versionRows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(version);
        when(version.executeQuery(anyString())).thenReturn(versionRows);
        when(versionRows.next()).thenReturn(true, false);
        when(versionRows.getString(1)).thenReturn(OscarNativeCatalog.SERVER_VERSION);
        var shell = mock(PreparedStatement.class);
        var shellRows = mock(ResultSet.class);
        var shellMetadata = mock(ResultSetMetaData.class);
        when(shell.executeQuery()).thenReturn(shellRows);
        when(shellRows.getMetaData()).thenReturn(shellMetadata);
        when(shellMetadata.getColumnCount()).thenReturn(2);
        for (int i = 1; i <= 2; i++) {
            when(shellMetadata.getColumnName(i)).thenReturn(i == 1 ? "A" : "B");
            when(shellMetadata.getColumnType(i)).thenReturn(jdbcType);
            when(shellMetadata.getColumnTypeName(i)).thenReturn(jdbcName);
            when(shellMetadata.getColumnClassName(i)).thenReturn(
                    jdbcType == Types.BIGINT ? "java.lang.Long" : "java.lang.Integer");
        }
        var columns = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(columns.executeQuery()).thenReturn(rows);
        var cursor = new AtomicInteger(-1);
        var wasNull = new AtomicBoolean();
        when(rows.next()).thenAnswer(invocation -> cursor.incrementAndGet() < ordinals.length);
        when(rows.getInt("ORDINAL_POSITION")).thenAnswer(invocation -> {
            wasNull.set(false); return ordinals[cursor.get()];
        });
        when(rows.getString("COLUMN_NAME")).thenAnswer(invocation -> catalogNames[cursor.get()]);
        when(rows.getString("TYPE_NAME")).thenReturn(nativeName);
        when(rows.getInt("DATA_TYPE")).thenAnswer(invocation -> {
            wasNull.set(nativeOid == null); return nativeOid == null ? 0 : nativeOid;
        });
        when(rows.getInt("NULLABLE")).thenAnswer(invocation -> { wasNull.set(false); return 1; });
        when(rows.wasNull()).thenAnswer(invocation -> wasNull.get());
        var keys = mock(PreparedStatement.class);
        when(keys.executeQuery()).thenReturn(mock(ResultSet.class));
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("WHERE 1=0") ? shell : sql.contains("V_SYS_COLUMNS") ? columns : keys;
        });
        return connection;
    }
}
