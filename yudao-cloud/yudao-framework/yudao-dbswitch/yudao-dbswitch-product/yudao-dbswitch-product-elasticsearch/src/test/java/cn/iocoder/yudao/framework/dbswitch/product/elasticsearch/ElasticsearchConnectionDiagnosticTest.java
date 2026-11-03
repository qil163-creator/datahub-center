package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseElasticsearchImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ElasticsearchConnectionDiagnosticTest {
    @Test void authenticatedButIllegalLogicalNamespaceIsNotConnectionSuccess() throws Exception {
        assertLogicalAndCleanup("illegal",false,false,"FAIL");
    }
    @Test void closeFailureWarnsWithoutErasingSuccessfulLogicalConnection() throws Exception {
        assertLogicalAndCleanup("es",true,true,"WARN");
    }
    @Test void failureRejectsArbitrarySqlStateAndOriginalMessage() throws Exception {
        var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,null,null,
                null,null,"es",null,java.util.Map.of(),java.time.Duration.ofSeconds(3));
        var spi=new ElasticsearchProductProvider().products().iterator().next().connectionProbe();
        for(String state:new String[]{"private-host-and-password","abcde","280000","28 00"}) {
            var result=spi.diagnose(request,()->{throw new SQLException("original-private-message",state);});
            assertNull(result.checks().getFirst().sqlState());assertNull(result.checks().getFirst().reasonCode());
            assertFalse(result.toString().contains(state));assertFalse(result.toString().contains("original-private-message"));
        }
    }
    private void assertLogicalAndCleanup(String database,boolean closeFails,boolean expectedConnection,String status) throws Exception {
        var c=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        if(closeFails) doThrow(new SQLException("private-close-error","private-close-state")).when(c).close();
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(c,65536,3)).thenReturn(session);
            when(session.request("GET","/",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    new ObjectMapper().readTree("{\"version\":{\"number\":\"8.0\"},\"cluster_name\":\"test\"}")));
            var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,null,null,
                    null,null,database,null,java.util.Map.of(),java.time.Duration.ofSeconds(3));
            var result=new ElasticsearchProductProvider().products().iterator().next().connectionProbe().diagnose(request,()->c);
            assertEquals(expectedConnection,result.connectionSuccessful());assertEquals(status,result.status());
            assertEquals("PASS",result.checks().getFirst().status());
            if(closeFails) {
                var cleanup=result.checks().stream().filter(x->x.key().equals("CLEANUP")).findFirst().orElseThrow();
                assertEquals("ADVANCED",cleanup.group());assertEquals("WARN",cleanup.status());
                assertEquals("诊断连接关闭未得到确认，需检查资源回收",cleanup.message());
                assertNull(cleanup.sqlState());assertNull(cleanup.reasonCode());
                assertFalse(result.toString().contains("private-close"));
            }
            verify(c).close();
        }
    }

    @Test void legacyProductionProbeAlsoRejectsMappingVisibleButReadDenied() throws Exception {
        var c=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(c,65536,3)).thenReturn(session);
            var json=new ObjectMapper();
            when(session.request("GET","/",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    json.readTree("{\"version\":{\"number\":\"8.0\"},\"cluster_name\":\"test\"}")));
            when(session.request("GET","/events/_mapping",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    json.readTree("{\"events\":{\"mappings\":{}}}")));
            when(session.request(eq("POST"),eq("/_security/user/_has_privileges"),anyString()))
                    .thenReturn(new ElasticsearchNativeSession.Response(200,json.readTree("{\"has_all_requested\":false}")));
            var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,null,null,
                    null,null,"es",null,java.util.Map.of("index","events"),java.time.Duration.ofSeconds(3));
            var spi=new ElasticsearchProductProvider().products().iterator().next().connectionProbe();
            assertThrows(SQLException.class,()->spi.probe(request,()->c));
            verify(c).close();
        }
    }
    @Test void productionSpiAuthenticationFailureIsNotConnectionSuccessAndIsRedacted() throws Exception {
        var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,null,null,
                null,null,"es",null,java.util.Map.of("index","events"),java.time.Duration.ofSeconds(3));
        var spi=new ElasticsearchProductProvider().products().iterator().next().connectionProbe();
        var result=spi.diagnose(request,()->{throw new SQLException("private-password-and-host","28000");});
        assertFalse(result.connectionSuccessful());assertEquals("FAIL",result.status());
        assertEquals("AUTH",result.checks().getFirst().key());assertEquals("28000",result.checks().getFirst().sqlState());
        assertFalse(result.toString().contains("private-password-and-host"));
    }
    @Test void productionSpiPreservesAuthenticationAndMappingWhenReadPrivilegeDenied() throws Exception {
        spiEvidence("events", "{\"has_all_requested\":false}", 200, "FAIL", true);
    }
    @Test void productionSpiRejectsMalformedPrivilegeEvidence() throws Exception {
        spiEvidence("events", "{}", 200, "FAIL", true);
    }
    @Test void productionSpiReportsSuccessfulPrivilegeCheck() throws Exception {
        spiEvidence("events", "{\"has_all_requested\":true}", 200, "PASS", true);
    }
    @Test void productionSpiMappingDenialPreservesAuthenticatedLogicalResource() throws Exception {
        spiEvidence("events", "{}", 403, "FAIL", true);
    }
    @Test void productionSpiWithoutIndexOnlyProvesLogicalNamespace() throws Exception {
        spiEvidence(null, "{}", 200, "PASS", false);
    }
    private void spiEvidence(String index,String privilege,int mappingStatus,String expected,boolean hasIndex) throws Exception {
        var c=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(c,65536,3)).thenReturn(session);
            var json=new ObjectMapper();
            when(session.request("GET","/",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    json.readTree("{\"version\":{\"number\":\"8.0\"},\"cluster_name\":\"test\"}")));
            when(session.request("GET","/events/_mapping",null)).thenReturn(new ElasticsearchNativeSession.Response(mappingStatus,
                    json.readTree("{\"events\":{\"mappings\":{}}}")));
            when(session.request(eq("POST"),eq("/_security/user/_has_privileges"),anyString()))
                    .thenReturn(new ElasticsearchNativeSession.Response(200,json.readTree(privilege)));
            var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,"jdbc:elasticsearch:test",null,
                    null,null,"es",null,index==null?java.util.Map.of():java.util.Map.of("index",index),java.time.Duration.ofSeconds(3));
            var probe=new ElasticsearchProductProvider().products().iterator().next().connectionProbe();
            var result=probe.diagnose(request,()->c);
            assertEquals(expected,result.status());assertTrue(result.connectionSuccessful());
            assertTrue(result.checks().stream().filter(x->x.status().equals("FAIL")).allMatch(x->x.reasonCode()==null));
            assertEquals("PASS",result.checks().stream().filter(x->x.key().equals("AUTH")).findFirst().orElseThrow().status());
            assertEquals("PASS",result.checks().stream().filter(x->x.key().equals("SCHEMA")).findFirst().orElseThrow().status());
            if(hasIndex) {
                assertEquals(mappingStatus==200?"PASS":"FAIL",result.checks().stream().filter(x->x.key().equals("INDEX_MAPPING")).findFirst().orElseThrow().status());
                if(mappingStatus==200) assertEquals(expected,result.checks().stream().filter(x->x.key().equals("INDEX_PRIVILEGES")).findFirst().orElseThrow().status());
            } else {
                verify(session,never()).request(eq("POST"),anyString(),any());
                verify(session,never()).request("GET","/events/_mapping",null);
                assertTrue(result.checks().stream().anyMatch(x->x.key().equals("INDEX_MAPPING")&&x.status().equals("SKIPPED")&&x.reasonCode().equals("UNVERIFIED")));
            }
            verify(c).close();
        }
    }

    @Test void namespaceReadsExactIndexMappingAndNeverSearchesDocuments() throws Exception {
        var connection=mock(Connection.class); var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class, CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.request("GET","/events/_mapping",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    new ObjectMapper().readTree("{\"events\":{\"mappings\":{\"properties\":{}}}}")));
            new DatabaseElasticsearchImpl().probeIndexMapping(connection,"events",3);
            verify(session).request("GET","/events/_mapping",null); verifyNoMoreInteractions(session);
        }
    }
    @Test void accountChecksEffectiveIndexReadAndMetadataPrivileges() throws Exception {
        var connection=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.request(eq("POST"),eq("/_security/user/_has_privileges"),anyString()))
                    .thenReturn(new ElasticsearchNativeSession.Response(200,new ObjectMapper().readTree("{\"has_all_requested\":true}")));
            new DatabaseElasticsearchImpl().probeIndexPrivileges(connection,"events",3);
            verify(session).request(eq("POST"),eq("/_security/user/_has_privileges"),
                    eq("{\"index\":[{\"names\":[\"events\"],\"privileges\":[\"read\",\"view_index_metadata\"]}]}"));
        }
    }

    @Test void deniedOrMalformedPermissionResponseCannotPass() throws Exception {
        var connection=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(connection,65536,3)).thenReturn(session);
            for(String json:new String[]{"{\"has_all_requested\":false}","{}"}) {
                when(session.request(eq("POST"),eq("/_security/user/_has_privileges"),anyString()))
                        .thenReturn(new ElasticsearchNativeSession.Response(200,new ObjectMapper().readTree(json)));
                assertThrows(SQLException.class,()->new DatabaseElasticsearchImpl().probeIndexPrivileges(connection,"events",3));
            }
        }
    }

    @Test void independentProbeDoesNotInterpretLogicalDatabaseEsAsIndex() throws Exception {
        var connection=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.request("GET","/",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    new ObjectMapper().readTree("{\"version\":{\"number\":\"8.0\"},\"cluster_name\":\"test\"}")));
            var request=new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH,null,"jdbc:elasticsearch:test",null,
                    null,null,"es",null,java.util.Map.of(),java.time.Duration.ofSeconds(3));
            new ElasticsearchConnectionProbe().probe(request,()->connection);
            verify(session).request("GET","/",null);verifyNoMoreInteractions(session);
        }
    }
    @Test void logicalNamespaceEsNeverRequestsPhysicalIndexEs() throws Exception {
        var connection=mock(Connection.class);var session=mock(ElasticsearchNativeSession.class);
        try(var sessions=mockStatic(ElasticsearchNativeSession.class,CALLS_REAL_METHODS)) {
            sessions.when(()->ElasticsearchNativeSession.open(connection,65536,3)).thenReturn(session);
            when(session.request("GET","/",null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                    new ObjectMapper().readTree("{\"version\":{\"number\":\"8.0\"},\"cluster_name\":\"test\"}")));
            new DatabaseElasticsearchImpl().probeNamespace(connection,"es",3);
            verify(session).request("GET","/",null);verifyNoMoreInteractions(session);
        }
    }
    @Test void namespaceRejectsWildcardAndMultiIndexSelectors() {
        assertThrows(IllegalArgumentException.class,()->new DatabaseElasticsearchImpl().probeNamespace(mock(Connection.class),"*",3));
        assertThrows(IllegalArgumentException.class,()->new DatabaseElasticsearchImpl().probeNamespace(mock(Connection.class),"a,b",3));
    }
}
