package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApiImpl;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiReleaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplyPublishedApiDocumentServiceTest {

    private final DataApiMapper apiMapper = mock(DataApiMapper.class);
    private final DataApiReleaseMapper releaseMapper = mock(DataApiReleaseMapper.class);
    private final DatabaseApi metadata = mock(DatabaseApi.class);
    private final SupplyPublishedApiDocumentService service = new SupplyPublishedApiDocumentService();
    private final DataApiDO current = new DataApiDO();
    private final DataApiReleaseDO release = new DataApiReleaseDO();
    private final DataApiDO published = new DataApiDO();

    @BeforeEach
    void setUp() {
        var publicationService = new DataApiServiceImpl();
        ReflectionTestUtils.setField(publicationService, "dataApiMapper", apiMapper);
        ReflectionTestUtils.setField(publicationService, "dataApiReleaseMapper", releaseMapper);
        var bindingService = new SupplyApiBindingService();
        ReflectionTestUtils.setField(bindingService, "databaseApi", metadata);
        ReflectionTestUtils.setField(service, "dataApiService", publicationService);
        ReflectionTestUtils.setField(service, "dataApiReleaseMapper", releaseMapper);
        ReflectionTestUtils.setField(service, "bindingService", bindingService);
        current.setId(7L);
        current.setStatus(DataApiDO.STATUS_ONLINE);
        current.setActiveReleaseId(70L);
        release.setId(70L);
        release.setApiId(7L);
        published.setName("已发布市民接口");
        published.setMethod("GET");
        published.setPath("/citizens");
        published.setVersion("v1");
        published.setConfigType(DataApiDO.CONFIG_TYPE_TABLE);
        published.setApiType(DataApiDO.API_TYPE_QUERY);
        published.setDatabaseId(3L);
        published.setTableName("Citizen");
        published.setRequestParamsJson("[{\"name\":\"ID\",\"paramType\":\"INTEGER\",\"nullable\":0,\"description\":\"编号筛选\"," +
                "\"defaultValue\":\"default_internal_marker\",\"exampleValue\":\"example_internal_marker\"}]");
        published.setResponseFieldsJson("[{\"name\":\"ID\",\"outputName\":\"citizenId\",\"dataType\":\"BIGINT\",\"description\":\"市民编号\"," +
                "\"exampleValue\":\"output_internal_marker\",\"modelRule\":\"rule_internal_marker\"}," +
                "{\"name\":\"Name\",\"dataType\":\"TEXT\"},{\"name\":\"Internal\",\"model\":\"HIDE\"}]");
        freeze();
        when(apiMapper.selectById(7L)).thenReturn(current);
        when(releaseMapper.selectById(70L)).thenReturn(release);
        var connection = new DatabaseConnectionRespDTO();
        connection.setSchema("Government");
        when(metadata.getDatabaseConnection(3L)).thenReturn(success(connection));
        when(metadata.getTableColumnList(3L, "Citizen")).thenReturn(success(List.of(
                new DatabaseColumnRespDTO("ID", "BIGINT"), new DatabaseColumnRespDTO("Name", "TEXT"),
                new DatabaseColumnRespDTO("Internal", "TEXT"))));
    }

    @Test
    void endpointProjectsPublishedSnapshotAndExcludesDraftAndExecutableConfiguration() {
        current.setName("draft_name_marker");
        current.setPath("/draft_path_marker");
        current.setMethod("POST");
        current.setSqlText("draft_sql_marker");
        current.setForwardUrl("draft_forward_marker");
        current.setDraftJson("{\"requestParamsJson\":\"draft_params_marker\"}");
        var endpoint = new SupplyDataApiApiImpl();
        ReflectionTestUtils.setField(endpoint, "publishedDocumentService", service);

        var document = endpoint.getPublishedApiDocument(7L).getCheckedData();

        assertThat(document.getName()).isEqualTo("已发布市民接口");
        assertThat(document.getMethod()).isEqualTo("GET");
        assertThat(document.getPath()).isEqualTo("/citizens");
        assertThat(document.getContractHash()).isEqualTo(release.getContractHash());
        assertThat(document.getActiveReleaseId()).isEqualTo(70L);
        assertThat(document.getRequestParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo("ID");
            assertThat(param.getType()).isEqualTo("INTEGER");
            assertThat(param.isRequired()).isTrue();
            assertThat(param.getDescription()).isEqualTo("编号筛选");
        });
        assertThat(document.getResponseFields()).extracting(SupplyPublishedApiDocumentRespDTO.ResponseField::getName)
                .containsExactly("citizenId", "Name");
        assertThat(document.getOutputFieldMapping()).containsExactlyInAnyOrderEntriesOf(Map.of("citizenId", "ID", "Name", "Name"));
        assertThat(document.isFieldMappingVerified()).isTrue();
        assertThat(document.getDatabaseId()).isEqualTo(3L);
        assertThat(document.getSchemaName()).isEqualTo("Government");
        assertThat(document.getTableName()).isEqualTo("Citizen");
        String json = JsonUtils.toJsonString(document);
        assertThat(json).doesNotContain("draft_", "default_internal_marker", "example_internal_marker",
                "output_internal_marker", "rule_internal_marker", "sqlText", "forwardUrl", "draftJson", "configJson",
                "defaultValue", "exampleValue", "modelRule", "authType", "Internal");
    }

    @Test
    void rejectsMissingOrOfflineApiBeforeReadingPublicationOrPhysicalMetadata() {
        assertCode(() -> service.getPublishedApiDocument(8L), 1_500_040_000);
        current.setStatus(DataApiDO.STATUS_OFFLINE);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_003);
        current.setStatus(null);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_003);
        verifyNoInteractions(releaseMapper, metadata);
    }

    @Test
    void refusesMissingReleaseEvidenceInsteadOfUsingCurrentRowAsLegacyDraft() {
        current.setActiveReleaseId(null);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        current.setActiveReleaseId(70L);
        current.setContractHash(" ");
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        verifyNoInteractions(releaseMapper, metadata);
    }

    @Test
    void rejectsMissingForeignOrUnrelatedPublication() {
        when(releaseMapper.selectById(70L)).thenReturn(null);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        when(releaseMapper.selectById(70L)).thenReturn(release);
        release.setApiId(8L);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        release.setApiId(7L);
        release.setId(71L);
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        release.setId(70L);
        release.setConfigJson(" ");
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_020);
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsRowAndReleaseContractDisagreement() {
        current.setContractHash("changed_contract_marker");
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_021);
        verifyNoInteractions(metadata);
    }

    @Test
    void recomputesFrozenContractAndConfigurationIncludingMethodPathAndVersion() {
        for (Consumer<DataApiDO> mutation : List.<Consumer<DataApiDO>>of(
                api -> api.setRequestParamsJson("[]"), api -> api.setMethod("POST"),
                api -> api.setPath("/changed"), api -> api.setVersion("v2"))) {
            DataApiContract.applySnapshot(published, JsonUtils.parseObject(release.getConfigJson(), Map.class));
            mutation.accept(published);
            String original = release.getConfigJson();
            release.setConfigJson(JsonUtils.toJsonString(DataApiContract.snapshot(published)));
            assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_021);
            release.setConfigJson(original);
        }
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsMalformedSnapshotWithDomainErrorWithoutEchoingRawConfiguration() {
        release.setConfigJson("{malformed_snapshot_marker");
        assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_005);
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsMalformedParameterDocumentsBeforeMetadataLookup() {
        for (String json : List.of("null", "{}", "[malformed_document_marker")) {
            published.setResponseFieldsJson(json);
            freeze();
            assertCode(() -> service.getPublishedApiDocument(7L), 1_500_040_005);
        }
        verifyNoInteractions(metadata);
    }

    @Test
    void explicitWildcardHasOnlyVerifiedNamesAndDoesNotInventPublishedTypes() {
        published.setResponseFieldsJson("[]");
        freeze();
        var document = service.getPublishedApiDocument(7L);
        assertThat(document.getResponseFields()).extracting(SupplyPublishedApiDocumentRespDTO.ResponseField::getName)
                .containsExactly("ID", "Internal", "Name");
        assertThat(document.getResponseFields()).allSatisfy(field -> {
            assertThat(field.getType()).isNull();
            assertThat(field.getDescription()).isNull();
        });
    }

    @Test
    void unprovenPhysicalMappingNeverBecomesVerifiedAndTransportErrorsDoNotBecomeFullAccess() {
        when(metadata.getTableColumnList(3L, "Citizen")).thenReturn(success(List.of()));
        var document = service.getPublishedApiDocument(7L);
        assertThat(document.isFieldMappingVerified()).isFalse();
        assertThat(document.getOutputFieldMapping()).isEmpty();
        when(metadata.getTableColumnList(3L, "Citizen")).thenThrow(new IllegalStateException("metadata unavailable"));
        assertThatThrownBy(() -> service.getPublishedApiDocument(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rawJsonUsesProjectLosslessIdsAndContainsOnlyPublicParameterFields() {
        var document = service.getPublishedApiDocument(7L);
        document.setId(Long.MAX_VALUE);
        var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        String json = mapper.writeValueAsString(document);
        var tree = mapper.readTree(json);
        assertThat(tree.get("id").isTextual()).isTrue();
        assertThat(tree.get("id").asString()).isEqualTo("9223372036854775807");
        assertThat(tree.get("activeReleaseId").isIntegralNumber()).isTrue();
        assertThat(tree.get("requestParams").get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("name", "type", "required", "description");
        assertThat(tree.get("responseFields").get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("name", "type", "description");
        assertThat(mapper.readValue(json, SupplyPublishedApiDocumentRespDTO.class)).isEqualTo(document);
    }

    private void freeze() {
        release.setConfigJson(JsonUtils.toJsonString(DataApiContract.snapshot(published)));
        release.setContractHash(DataApiContract.contractHash(published));
        release.setConfigHash(DataApiContract.configHash(published));
        current.setContractHash(release.getContractHash());
    }

    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class).satisfies(error -> {
            assertThat(((ServiceException) error).getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("marker", "sqlText", "defaultValue");
        });
    }

}
