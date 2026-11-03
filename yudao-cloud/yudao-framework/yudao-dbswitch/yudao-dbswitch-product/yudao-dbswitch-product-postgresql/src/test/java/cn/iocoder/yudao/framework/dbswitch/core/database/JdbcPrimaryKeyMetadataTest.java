package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcPrimaryKeyMetadataTest {
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet rows = mock(ResultSet.class);

    private void setup() throws SQLException {
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getPrimaryKeys(null, "schema", "table")).thenReturn(rows);
    }

    @Test void keySequenceWinsOverAlphabeticalDriverRowOrderAndHashOrder() throws Exception {
        setup();
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("COLUMN_NAME")).thenReturn("a", "z");
        when(rows.getInt("KEY_SEQ")).thenReturn(2, 1);
        assertEquals(List.of("z", "a"), new DatabasePostgresImpl().queryTablePrimaryKeys(connection, "schema", "table"));
        verify(rows).close();
        verify(connection, never()).close();
    }

    @Test void keylessTableReturnsAnEmptyList() throws Exception {
        setup();
        assertEquals(List.of(), new DatabasePostgresImpl().queryTablePrimaryKeys(connection, "schema", "table"));
        verify(rows).close();
    }

    @ParameterizedTest @ValueSource(strings={"duplicate-sequence", "gap", "zero", "null-sequence", "null-name", "empty-name", "duplicate-name"})
    void invalidOrIncompleteKeyEvidenceIsRejectedInsteadOfSilentlyDeduplicated(String fault) throws Exception {
        setup();
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("COLUMN_NAME")).thenReturn("a", switch (fault) {
            case "null-name" -> null;
            case "empty-name" -> "";
            case "duplicate-name" -> "a";
            default -> "z";
        });
        when(rows.getInt("KEY_SEQ")).thenReturn(1, switch (fault) {
            case "duplicate-sequence" -> 1;
            case "gap" -> 3;
            case "zero" -> 0;
            default -> 2;
        });
        if (fault.equals("null-sequence")) when(rows.wasNull()).thenReturn(false, true);
        var failure = assertThrows(RuntimeException.class,
                () -> new DatabasePostgresImpl().queryTablePrimaryKeys(connection, "schema", "table"));
        assertInstanceOf(SQLException.class, failure.getCause());
        verify(rows).close();
        verify(connection, never()).close();
    }
}
