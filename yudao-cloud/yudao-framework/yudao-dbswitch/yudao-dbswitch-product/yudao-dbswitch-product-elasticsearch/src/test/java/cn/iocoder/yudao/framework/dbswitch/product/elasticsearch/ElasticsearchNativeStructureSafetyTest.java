package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElasticsearchNativeStructureSafetyTest {
    private final ObjectMapper json = new ObjectMapper();
    private DataSource source;
    private Connection connection;
    private ElasticsearchNativeSession session;
    private MockedStatic<ElasticsearchNativeSession> nativeOpen;
    @BeforeEach void setup() throws Exception {
        source = mock(DataSource.class); connection = mock(Connection.class); session = mock(ElasticsearchNativeSession.class);
        when(source.getConnection()).thenReturn(connection);
        nativeOpen = mockStatic(ElasticsearchNativeSession.class, CALLS_REAL_METHODS);
        nativeOpen.when(() -> ElasticsearchNativeSession.open(any(), anyLong(), anyInt())).thenReturn(session);
        when(session.request("GET", "/", null)).thenReturn(response("{\"version\":{\"number\":\"8.15.3\"}}"));
        when(session.request("GET", "/_nodes/plugins?filter_path=nodes.*.plugins", null)).thenReturn(response("{\"nodes\":{\"node\":{\"plugins\":[]}}}"));
        when(session.request("GET", "/_resolve/index/sample?expand_wildcards=all", null)).thenReturn(response("{\"indices\":[{\"name\":\"sample\",\"attributes\":[\"open\"]}],\"aliases\":[],\"data_streams\":[]}"));
        when(session.request(eq("POST"), eq("/sample/_count"), anyString())).thenReturn(response("{\"count\":0,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
    }
    @AfterEach void close() { nativeOpen.close(); }

    @Test void sourceFieldListAndDefinitionComeFromOnePhysicalObservation() throws Exception {
        when(session.request("GET", "/sample?flat_settings=true", null)).thenReturn(response(
                "{\"sample\":{\"mappings\":{\"properties\":{\"observed\":{\"type\":\"long\"}}},"
                        + "\"settings\":{\"index.uuid\":\"physical-generation\",\"index.number_of_shards\":\"1\"}}}"));
        var definition = ElasticsearchNativeStructure.describe(source, "sample", 1024, 2);
        assertEquals(List.of("_id", "observed"), definition.fields());
        assertEquals("physical-generation", json.readTree(definition.document()).path("sourceIdentity").textValue());
        verify(session, never()).request("GET", "/sample/_mapping", null);
        verify(connection).close();
    }

    @Test void complexNativeTypeIsDescribedButCannotAuthorizeAFlatDataFrame() throws Exception {
        when(session.request("GET", "/sample?flat_settings=true", null)).thenReturn(response(
                "{\"sample\":{\"mappings\":{\"properties\":{\"value\":{\"type\":\"scaled_float\",\"scaling_factor\":100}}},"
                        + "\"settings\":{\"index.uuid\":\"physical-generation\",\"index.number_of_shards\":\"1\"}}}"));
        var definition = ElasticsearchNativeStructure.describe(source, "sample", 1024, 2);
        assertEquals(List.of("_id", "value"), definition.fields());
        assertEquals(java.sql.Types.OTHER, ElasticsearchNativeStructure.columns(definition).get(1).getFieldType());
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchStructureContract.dataFrame(
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(definition.document()).path("body")));
        verify(session, never()).request(eq("DELETE"), anyString(), any());
        verify(connection).close();
    }

    @Test void unprovedTypeOrMismatchedFieldsCannotAuthorizeAnyTargetRequest() {
        var unsupported = new NativeTableDefinition(ProductTypeEnum.ELASTICSEARCH, "elasticsearch-index-v1",
                "{\"major\":8,\"sourceIdentity\":\"source\",\"body\":{\"mappings\":{\"properties\":{\"value\":{\"type\":\"scaled_float\",\"scaling_factor\":100}}},\"settings\":{}}}",
                List.of("_id", "value"));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchNativeStructure.prepare(source, unsupported,
                "sample", Map.of("_id", "_id", "value", "value"), true, 1024, 2));
        var inconsistent = new NativeTableDefinition(ProductTypeEnum.ELASTICSEARCH, "elasticsearch-index-v1",
                "{\"major\":8,\"sourceIdentity\":\"source\",\"body\":{\"mappings\":{\"properties\":{\"value\":{\"type\":\"long\"}}},\"settings\":{}}}",
                List.of("_id", "other"));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchNativeStructure.prepare(source, inconsistent,
                "sample", Map.of("_id", "_id", "other", "other"), true, 1024, 2));
        verifyNoInteractions(session);
    }

    private ElasticsearchNativeSession.Response response(String body) throws Exception {
        return new ElasticsearchNativeSession.Response(200, json.readTree(body));
    }
}
