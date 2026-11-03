package cn.iocoder.yudao.framework.dbswitch.product.phoenix;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import org.junit.jupiter.api.Test;
import java.sql.*;import java.time.Duration;import java.util.Map;
import static org.mockito.Mockito.*;
class PhoenixConnectionProbeTest {
    @Test void diagnoseReturnsMetadataAndExplicitNamespaceLimitationInsteadOfLosingEvidence() throws Exception {
        var c=mock(Connection.class);var s=mock(Statement.class);var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery(anyString())).thenReturn(r);when(r.next()).thenReturn(true);
        var request=new ConnectionProbeRequest(ProductTypeEnum.PHOENIX,null,"jdbc:phoenix:localhost",null,"user",null,null,"analytics",Map.of(),Duration.ofSeconds(3));
        var result=new PhoenixProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("AUTH")&&x.status().equals("PASS")));
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.group().equals("METADATA")&&x.status().equals("PASS")));
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("TARGET")&&"UNVERIFIED".equals(x.reasonCode())));
        org.junit.jupiter.api.Assertions.assertFalse(result.connectionSuccessful()); verify(c).close();
    }

    @Test void missingDriverRemainsVisibleToSharedDependencyClassifier() {
        var request=new ConnectionProbeRequest(ProductTypeEnum.PHOENIX,null,"jdbc:phoenix:localhost",null,"user",null,null,null,Map.of(),Duration.ofSeconds(3));
        org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class,()->
                new PhoenixProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->{throw new ClassNotFoundException("private-driver");}));
    }
    @Test void unverifiedNamespaceNeverClaimsSuccess() {
        var request=new ConnectionProbeRequest(ProductTypeEnum.PHOENIX,null,"jdbc:phoenix:localhost",null,
                "user",null,null,"analytics",Map.of(),Duration.ofSeconds(3));
        var connection=mock(Connection.class);
        org.junit.jupiter.api.Assertions.assertThrows(SQLFeatureNotSupportedException.class,
                ()->new PhoenixProductProvider().products().iterator().next().connectionProbe().probe(request,()->connection));
    }

    @Test void readsSystemCatalogRatherThanOnlyConstantPing() throws Exception {
        var connection=mock(Connection.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rows);when(rows.next()).thenReturn(true);
        var request=new ConnectionProbeRequest(ProductTypeEnum.PHOENIX,null,"jdbc:phoenix:localhost",null,
                "user",null,null,null,Map.of(),Duration.ofSeconds(3));
        new PhoenixProductProvider().products().iterator().next().connectionProbe().probe(request,()->connection);
        verify(statement).executeQuery("SELECT TABLE_NAME FROM SYSTEM.CATALOG LIMIT 1");
        verify(statement).setQueryTimeout(3);verify(connection).close();
    }
}
