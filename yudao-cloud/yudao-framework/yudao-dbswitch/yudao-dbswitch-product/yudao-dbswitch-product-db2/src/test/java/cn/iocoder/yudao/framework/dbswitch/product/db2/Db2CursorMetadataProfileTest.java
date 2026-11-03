package cn.iocoder.yudao.framework.dbswitch.product.db2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Db2CursorMetadataProfileTest {
    @ParameterizedTest @ValueSource(strings = {"SQL12015", "SQL120150"})
    void driverVersionTokensStillRequireTheExactNativeServiceLevel(String token) throws Exception {
        var connection = connection("DB2/LINUXX8664", token);
        var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT service_level FROM SYSIBMADM.ENV_INST_INFO")).thenReturn(rows);
        when(rows.next()).thenReturn(true); when(rows.getString(1)).thenReturn("DB2 v12.1.5.0");
        Db2CursorMetadata.product(connection);
        verify(statement).setQueryTimeout(15); verify(statement).close(); verify(rows).close();
        when(rows.getString(1)).thenReturn("DB2 v12.1.6.0");
        assertThrows(SQLException.class, () -> Db2CursorMetadata.product(connection));
    }

    @ParameterizedTest @CsvSource({"DB2/LINUXX8664,SQL120160", "DB2/LINUXX8664,SQL11059", "DB2,SQL120150"})
    void unverifiedProfilesAreRejectedBeforeCatalogQueries(String product, String version) throws Exception {
        var connection = connection(product, version);
        assertThrows(SQLException.class, () -> Db2CursorMetadata.product(connection));
        verify(connection, never()).createStatement();
    }

    private static Connection connection(String product, String version) throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product); when(metadata.getDatabaseProductVersion()).thenReturn(version);
        return connection;
    }
}
