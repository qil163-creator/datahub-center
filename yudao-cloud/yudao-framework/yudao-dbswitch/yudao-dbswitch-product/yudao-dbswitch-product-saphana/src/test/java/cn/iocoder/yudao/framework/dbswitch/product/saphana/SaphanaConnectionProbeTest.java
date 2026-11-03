package cn.iocoder.yudao.framework.dbswitch.product.saphana;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import org.junit.jupiter.api.Test;
import java.sql.*;import java.time.Duration;import java.util.Map;
import static org.mockito.Mockito.*;
class SaphanaConnectionProbeTest {
    @Test void diagnosticPreservesIdentityResponseAfterSetSchemaDenial() throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery("SELECT CURRENT_USER FROM DUMMY")).thenReturn(r); when(r.next()).thenReturn(true);
        doThrow(new SQLException("private-account","42501",9)).when(s).execute("SET SCHEMA \"WAREHOUSE\"");
        var request=new ConnectionProbeRequest(ProductTypeEnum.SAPHANA,null,"jdbc:sap://localhost:30015",null,"user",null,null,"WAREHOUSE",Map.of(),Duration.ofSeconds(3));
        var result=new SaphanaProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
        org.junit.jupiter.api.Assertions.assertEquals("FAIL",result.status());
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("AUTH")&&x.status().equals("PASS")));
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.status().equals("FAIL")&&"42501".equals(x.sqlState())));
        org.junit.jupiter.api.Assertions.assertTrue(result.checks().stream().anyMatch(x->x.key().equals("SCHEMA_METADATA")&&"PREREQUISITE_FAILED".equals(x.reasonCode())));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("private-account")); verify(c).close(); verify(r).close();
    }

    @Test void targetSchemaIsVerifiedThroughSystemMetadata() throws Exception {
        var c=mock(Connection.class);var s=mock(Statement.class);var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery(anyString())).thenReturn(r);when(r.next()).thenReturn(true);
        var request=new ConnectionProbeRequest(ProductTypeEnum.SAPHANA,null,"jdbc:sap://localhost:30015",null,
                "user",null,null,"WAREHOUSE",Map.of(),Duration.ofSeconds(3));
        new SaphanaProductProvider().products().iterator().next().connectionProbe().probe(request,()->c);
        verify(s).execute("SET SCHEMA \"WAREHOUSE\""); verify(s).executeQuery("SELECT CURRENT_USER FROM DUMMY");
        verify(s,atLeastOnce()).setQueryTimeout(3);verify(c).close();
    }
}
