package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleKeyProjectionTest {
    private final IDatabaseInterface database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.ORACLE);
    @Test void officialCompanionManifestDoesNotBypassStandaloneDriverPin()throws Exception{
        var path=java.nio.file.Path.of(Class.forName("oracle.jdbc.OracleDriver").getProtectionDomain().getCodeSource().getLocation().toURI());
        var config=new cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties.Driver();
        config.setPath(path.toString());
        config.setSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path))));
        var failure=assertThrows(IllegalArgumentException.class,()->new cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers(java.util.Map.of(ProductTypeEnum.ORACLE,config)));
        assertTrue(failure.getMessage().contains("external JAR dependencies are not pinned"));
    }
    @ParameterizedTest @CsvSource({"NUMBER,2,19,0,-5","NUMBER,2,38,0,-5","NUMBER,2,1,0,-5",
            "NUMBER,2,0,0,0","NUMBER,2,0,-127,0","NUMBER,2,19,2,0","NUMBER,2,19,-2,0",
            "NUMBER,2,126,-127,0","BINARY_DOUBLE,101,0,0,0","VARCHAR2,12,19,0,0","NUMBER,3,19,0,0"})
    void requiresNativeDeclaredIntegralNumber(String type,int jdbc,int precision,int scale,int expected)throws Exception{
        var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnTypeName(1)).thenReturn(type);
        when(metadata.getColumnType(1)).thenReturn(jdbc);when(metadata.getPrecision(1)).thenReturn(precision);
        when(metadata.getScale(1)).thenReturn(scale);when(metadata.isSigned(1)).thenReturn(true);
        assertEquals(expected,database.integerKeyProjection("k","ID").jdbcType(metadata,1));
        when(metadata.isSigned(1)).thenReturn(false);assertEquals(0,database.integerKeyProjection("k","ID").jdbcType(metadata,1));
    }
    @Test void boundedNativeSqlPreservesWitnessAndExactPhysicalNames(){
        assertEquals("CASE WHEN 1=0 THEN k.\"ID\" END",database.integerKeyProjection("k","ID").typeSql());
        assertTrue(database.integerKeyProjection(null,"ID").valueSql().contains("NLS_NUMERIC_CHARACTERS=''.,''"));
        assertTrue(database.keyComponentProjection(null,"ID",3).valueSql().contains("<= 3"));
        assertEquals("SELECT x FROM t ORDER BY x FETCH FIRST 7 ROWS ONLY",database.limitGeneratedSelect("SELECT x FROM t ORDER BY x",7));
        assertEquals("\"Owner\".\"ta'ble\"",database.getQuotedSchemaTableCombination("Owner","ta'ble"));
        assertThrows(IllegalArgumentException.class,()->database.getQuotedSchemaTableCombination("Owner","t\"able"));
        assertThrows(IllegalArgumentException.class,()->database.integerKeyProjection("k; DROP","ID"));
        assertThrows(IllegalArgumentException.class,()->database.integerKeyProjection(null,"ID\""));
        assertThrows(IllegalArgumentException.class,()->database.keyComponentProjection(null,"ID",0));
        assertThrows(IllegalArgumentException.class,()->database.keyComponentProjection(null,"ID",257));
        assertThrows(IllegalArgumentException.class,()->database.getQuotedSchemaTableCombination("S","界".repeat(43)));
    }
    @Test void unverifiedVersionsDoNotSilentlyEnableProjection()throws Exception{
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("Oracle");
        when(metadata.getDatabaseMajorVersion()).thenReturn(19);
        assertThrows(SQLFeatureNotSupportedException.class,()->database.integerKeyProjection(connection,"k","ID"));
        when(metadata.getDatabaseMajorVersion()).thenReturn(23);assertNotNull(database.integerKeyProjection(connection,"k","ID"));
        when(metadata.getDatabaseProductName()).thenReturn("compatible");
        assertThrows(SQLFeatureNotSupportedException.class,()->database.keyComponentProjection(connection,"k","ID",21));
        verify(connection,never()).close();verify(connection,never()).setAutoCommit(anyBoolean());
    }
}
