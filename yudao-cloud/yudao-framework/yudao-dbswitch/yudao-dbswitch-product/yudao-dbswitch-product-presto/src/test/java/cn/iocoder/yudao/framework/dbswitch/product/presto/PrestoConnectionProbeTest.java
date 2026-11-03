package cn.iocoder.yudao.framework.dbswitch.product.presto;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import org.junit.jupiter.api.Test;
import java.sql.*;import java.time.Duration;import java.util.Map;
import static org.mockito.Mockito.*;
class PrestoConnectionProbeTest {
    @Test void diagnosticRetainsAccountResponseAfterTargetDenialAndStripsMalformedSqlState() throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class); var target=mock(PreparedStatement.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery("SELECT current_user")).thenReturn(r); when(r.next()).thenReturn(true);
        when(c.prepareStatement(anyString())).thenReturn(target); when(target.executeQuery()).thenThrow(new SQLException("private-password","bad-state-secret",47));
        var request=new ConnectionProbeRequest(ProductTypeEnum.PRESTO,null,"jdbc:presto://localhost:8080/warehouse/analytics",null,"user",null,"warehouse","analytics",Map.of(),Duration.ofSeconds(3));
        var result=new PrestoProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
        org.junit.jupiter.api.Assertions.assertEquals("FAIL",result.status());
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("AUTH")&&x.status().equals("PASS")));
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("SCHEMATA")&&"PREREQUISITE_FAILED".equals(x.reasonCode())));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("secret"));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("private-password")); verify(c).close(); verify(r).close(); verify(target).close();
    }

    @Test void catalogWithoutSchemaStillRequiresCatalogMetadataResponse() throws Exception {
        var connection=mock(Connection.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        var request=new ConnectionProbeRequest(ProductTypeEnum.PRESTO,null,"jdbc:presto://localhost:8080/",null,
                "user",null,"warehouse",null,Map.of(),Duration.ofSeconds(3));
        new PrestoProductProvider().products().iterator().next().connectionProbe().probe(request,()->connection);
        verify(statement).executeQuery("SELECT schema_name FROM \"warehouse\".information_schema.schemata LIMIT 1");
    }

    @Test void explicitCatalogAndSchemaRequireActualMetadataResponse() throws Exception {
        var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        var fallback=mock(Statement.class);when(c.createStatement()).thenReturn(fallback);when(fallback.executeQuery(anyString())).thenReturn(r);
        when(c.prepareStatement("SELECT schema_name FROM \"warehouse\".information_schema.schemata WHERE schema_name = ?")).thenReturn(s);
        when(s.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true);when(r.getString(1)).thenReturn("analytics");
        var request=new ConnectionProbeRequest(ProductTypeEnum.PRESTO,null,"jdbc:presto://localhost:8080/warehouse/analytics",null,
                "user",null,"warehouse","analytics",Map.of(),Duration.ofSeconds(3));
        new PrestoProductProvider().products().iterator().next().connectionProbe().probe(request,()->c);
        verify(s).setString(1,"analytics");verify(s).setQueryTimeout(3);verify(c).close();
    }
}
