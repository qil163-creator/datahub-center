package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplyApiBindingServiceTest {
    private final DatabaseApi metadata = mock(DatabaseApi.class);
    private final SupplyApiBindingService service = new SupplyApiBindingService();
    private final DatabaseConnectionRespDTO connection = new DatabaseConnectionRespDTO();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "databaseApi", metadata);
        connection.setSchema("Government");
        when(metadata.getDatabaseConnection(3L)).thenReturn(success(connection));
        when(metadata.getTableColumnList(3L, "Citizen")).thenReturn(success(List.of(
                new DatabaseColumnRespDTO("ID", "bigint"), new DatabaseColumnRespDTO("Name", "text"))));
    }

    @Test
    void preservesRealSchemaPhysicalCaseAndPublishedOutputAlias() {
        var result = service.resolve(api("[{\"name\":\"ID\",\"outputName\":\"citizenId\"}]"));
        assertThat(result.getSchemaName()).isEqualTo("Government");
        assertThat(result.isFieldMappingVerified()).isTrue();
        assertThat(result.getOutputFieldMapping()).containsOnlyKeys("citizenId").containsEntry("citizenId", "ID");
    }

    @Test
    void missingSchemaIsNotGuessedAndSkipsColumnLookup() {
        connection.setSchema(null);
        var result = service.resolve(api("[]"));
        assertThat(result.isFieldMappingVerified()).isFalse();
        assertThat(result.getSchemaName()).isNull();
        verify(metadata, never()).getTableColumnList(anyLong(), anyString());
    }

    @Test
    void rejectsCaseMismatchMissingColumnOrAmbiguousOutput() {
        for (var json : List.of("[{\"name\":\"id\"}]", "[{\"name\":\"unknown\"}]",
                "[{\"name\":\"ID\",\"outputName\":\"same\"},{\"name\":\"Name\",\"outputName\":\"same\"}]")) {
            var result = service.resolve(api(json));
            assertThat(result.isFieldMappingVerified()).isFalse();
            assertThat(result.getOutputFieldMapping()).isEmpty();
        }
    }

    @Test
    void excludesHiddenFieldsAndFreezesExplicitPhysicalWildcardExpansion() {
        assertThat(service.resolve(api("[{\"name\":\"ID\"},{\"name\":\"Name\",\"model\":\"HIDE\"}]"))
                .getOutputFieldMapping()).containsOnlyKeys("ID");
        var result = service.resolve(api("[]"));
        assertThat(result.isFieldMappingVerified()).isTrue();
        assertThat(result.getOutputFieldMapping()).containsOnlyKeys("ID", "Name");
        assertThatThrownBy(() -> result.getOutputFieldMapping().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unconfirmedLegacyOrChangedContractDoesNotBecomeApprovalEvidence() {
        var legacy = api("[]");
        legacy.setActiveReleaseId(null);
        assertThat(service.resolve(legacy).getContractHash()).isNull();
        var changed = api("[]");
        changed.setResponseFieldsJson("[{\"name\":\"ID\"}]");
        assertThat(service.resolve(changed).isFieldMappingVerified()).isFalse();
        verifyNoInteractions(metadata);
    }

    @Test
    void metadataTransportFailureDoesNotBecomeEmptyFullAccess() {
        when(metadata.getTableColumnList(3L, "Citizen")).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.resolve(api("[]"))).isInstanceOf(IllegalStateException.class);
    }

    private static DataApiDO api(String fields) {
        var api = new DataApiDO();
        api.setId(7L);
        api.setDatabaseId(3L);
        api.setTableName("Citizen");
        api.setVersion("v1");
        api.setActiveReleaseId(70L);
        api.setStatus(DataApiDO.STATUS_ONLINE);
        api.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        api.setApiType(DataApiDO.API_TYPE_QUERY);
        api.setResponseFieldsJson(fields);
        api.setContractHash(DataApiContract.contractHash(api));
        return api;
    }
}
