package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditOceanbaseDialectTest {
    @Test void zeroAndWhitespaceAreNotComparedToEmptyStringUsingPadSpaceCollation() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.OCEANBASE);
        context.setTableName("sample"); context.setColumn("amount"); context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        String sql = generator.buildCountSql(context);
        assertTrue(sql.contains("LENGTH(CAST(`amount` AS CHAR)) > 0"), sql); assertFalse(sql.contains("<> ''"));
        context.setConfig(Map.of("allowBlank", true));
        assertFalse(generator.buildCountSql(context).contains("LENGTH"));
    }

    @Test void actualOracleOrUnknownConnectionModeCannotUseMysqlQualitySql() throws Exception {
        for (String mode : new String[]{"ORACLE", "UNKNOWN"}) {
            var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("5.7.25-OceanBase_CE-v4.3.5.6");
            when(rows.getString(2)).thenReturn(mode);
            assertThrows(SQLFeatureNotSupportedException.class, () -> AuditSqlDialect.validateConnection(connection, ProductTypeEnum.OCEANBASE));
            verify(statement).close(); verify(rows).close(); verify(connection, never()).close();
        }
    }

    /** 除 OceanBase 模式核对外，只有 PostgreSQL 系需要核对 standard_conforming_strings；其他产品不做额外探测。 */
    @Test void otherProductSqlContractsDoNotRequireAnExtraProbe() throws Exception {
        for (var product : ProductTypeEnum.values()) {
            if (product == ProductTypeEnum.OCEANBASE) continue;
            var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery("SHOW standard_conforming_strings")).thenReturn(rows);
            when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("on");
            AuditSqlDialect.validateConnection(connection, product);
            if (product.isLikePostgres()) {
                verify(statement).executeQuery("SHOW standard_conforming_strings");
                verify(statement).close(); verify(rows).close();
            } else {
                verifyNoInteractions(connection);
            }
            verify(connection, never()).prepareStatement(anyString());
            verify(connection, never()).close();
        }
    }
}
