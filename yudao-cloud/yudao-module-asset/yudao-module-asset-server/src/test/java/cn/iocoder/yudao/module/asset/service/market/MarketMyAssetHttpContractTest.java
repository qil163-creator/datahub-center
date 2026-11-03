package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketCategoryController;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketMyAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** DispatcherServlet、权限代理、参数绑定和真实领域编排的 HTTP 契约；远端系统及本地 Mapper 为边界替身。 */
class MarketMyAssetHttpContractTest {
    private static final long TABLE_GRANT = 9007199254740993L;
    private static final String PREFIX = "/admin-api/asset/market/my-assets";
    private static final String PERMISSION = "asset:market:my-assets:query";
    private static final LocalDateTime NOW = LocalDateTime.of(2040, 1, 1, 0, 0);
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private final MarketMyAssetMapper mine = mock(MarketMyAssetMapper.class);
    private final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MarketDeliveryOperationMapper operations = mock(MarketDeliveryOperationMapper.class);
    private final MarketApprovalEvidenceMapper approvals = mock(MarketApprovalEvidenceMapper.class);
    private final MarketCheckProcessMapper processes = mock(MarketCheckProcessMapper.class);
    private final MarketCategoryMapper categories = mock(MarketCategoryMapper.class);
    private final DatabaseApi dataApi = mock(DatabaseApi.class);
    private final SupplyDataApiApi supplyApi = mock(SupplyDataApiApi.class);
    private final SupplyQuotaApi quotaApi = mock(SupplyQuotaApi.class);
    private final MetadataApi metadataApi = mock(MetadataApi.class);
    private MarketMyAssetRow table;
    private MarketMyAssetRow api;
    private SupplyPublishedApiDocumentRespDTO document;

    @BeforeEach
    void configureRealHttpAndDomainBoundary() {
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        JsonUtils.init(json);
        context = new AnnotationConfigApplicationContext(SecurityConfig.class);
        var permissionApi = context.getBean(PermissionCommonApi.class);
        when(permissionApi.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            long user = call.getArgument(0);
            String[] requested = (String[]) call.getRawArguments()[1];
            Set<String> permitted = user == 9 ? Set.of(PERMISSION) : Set.of("asset:market:query");
            return CommonResult.success(java.util.Arrays.stream(requested).anyMatch(permitted::contains));
        });
        login(9L);
        table = row(TABLE_GRANT, 5L, false);
        api = row(8L, 6L, true);
        var tableAsset = asset(5L); var apiAsset = asset(6L);
        when(assets.selectByIds(anyCollection())).thenReturn(List.of(tableAsset, apiAsset));
        when(assets.selectById(5L)).thenReturn(tableAsset); when(assets.selectById(6L)).thenReturn(apiAsset);
        when(applies.databaseNow()).thenReturn(NOW);
        when(applies.selectForAccess(TABLE_GRANT, 9L)).thenReturn(table);
        when(applies.selectForAccess(8L, 9L)).thenReturn(api);
        when(applies.selectQuerySources(eq(5L), eq(9L), eq(NOW), anyInt())).thenReturn(List.of(table));
        when(applies.selectQuerySources(eq(6L), eq(9L), eq(NOW), anyInt())).thenReturn(List.of(api));
        when(mine.selectOwned(9L, TABLE_GRANT)).thenReturn(table);
        when(mine.selectOwned(9L, 8L)).thenReturn(api);
        when(mine.countOwned(eq(9L), any(), any())).thenReturn(2L);
        when(mine.selectOwnedPage(eq(9L), any(), any(), anyLong())).thenReturn(List.of(table, api));
        var category = new MarketCategoryDO(); category.setId(2L); category.setParentId(0L); category.setName("当前集市"); category.setStatus((short) 0);
        when(categories.selectCategoryList(any())).thenReturn(List.of(category));
        when(categories.selectById(2L)).thenReturn(category);
        var categoryService = new MarketCategoryServiceImpl(); ReflectionTestUtils.setField(categoryService, "categoryMapper", categories);
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "operationMapper", operations);
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", approvals);
        configureConfirmedApiDelivery(delivery);
        var identity = new AssetMetadataIdentityGuard(); ReflectionTestUtils.setField(identity, "metadataApi", metadataApi);
        configureMetadata(tableAsset); configureMetadata(apiAsset);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var consumption = new MarketConsumptionService(); ReflectionTestUtils.setField(consumption, "quotaApi", quotaApi);
        var access = new MarketAccessServiceImpl();
        Map.ofEntries(Map.entry("applyMapper", applies), Map.entry("assetMapper", assets),
                Map.entry("transactionManager", transactions), Map.entry("metadataIdentityGuard", identity),
                Map.entry("databaseApi", dataApi), Map.entry("consumptionService", consumption),
                Map.entry("assetMonitorService", mock(AssetMonitorService.class)))
                .forEach((name, value) -> ReflectionTestUtils.setField(access, name, value));
        var service = new MarketMyAssetServiceImpl();
        Map.ofEntries(Map.entry("myAssetMapper", mine), Map.entry("applyMapper", applies), Map.entry("assetMapper", assets),
                Map.entry("deliveryMapper", operations), Map.entry("approvalMapper", approvals), Map.entry("processMapper", processes),
                Map.entry("deliveryService", delivery), Map.entry("categoryService", categoryService),
                Map.entry("accessService", access), Map.entry("supplyDataApiApi", supplyApi))
                .forEach((name, value) -> ReflectionTestUtils.setField(service, name, value));
        ReflectionTestUtils.setField(context.getBean(MarketMyAssetController.class), "service", service);
        ReflectionTestUtils.setField(context.getBean(MarketCategoryController.class), "service", categoryService);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(MarketMyAssetController.class), context.getBean(MarketCategoryController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties()).getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("asset-http-contract", mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "VARCHAR"), new DatabasePreviewRespDTO.Column("Secret", "未批准字段", "VARCHAR")));
        preview.setRows(List.of(Map.of("Id", "1", "Secret", "must-not-leak")));
        when(dataApi.previewTable(any())).thenReturn(CommonResult.success(preview));
        document = document();
        when(supplyApi.getPublishedApiDocument(21L)).thenReturn(CommonResult.success(document));
        when(quotaApi.reserve(any())).thenReturn(CommonResult.success(new SupplyQuotaDTO.Receipt("PORTAL:9:5", "http-request-0001", "RESERVED", "test-reservation", 1, null, List.of())));
        when(quotaApi.settle(any())).thenReturn(CommonResult.success(new SupplyQuotaDTO.Receipt("PORTAL:9:5", "http-request-0001", "COMMITTED", null, 1, 1, List.of())));
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (validator != null) validator.close();
        if (context != null) context.close();
    }

    @Test
    void independentPermissionAdmitsFourRealGetRoutesWithDomainResponses() throws Exception {
        assertThat(request(get(PREFIX + "/page")).get("code").intValue()).isZero();
        var detail = request(get(PREFIX + "/get").param("id", String.valueOf(TABLE_GRANT)));
        assertThat(detail.get("code").intValue()).isZero();
        assertThat(detail.get("data").get("applyId").asString()).isEqualTo(String.valueOf(TABLE_GRANT));
        assertThat(detail.get("data").get("createTime").isNumber()).isTrue();
        assertThat(request(get(PREFIX + "/preview").param("id", String.valueOf(TABLE_GRANT)).header("X-Consumption-Key", "http-request-0001")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/api-document").param("id", "8")).get("code").intValue()).isZero();
        var gate = context.getBean(PermissionCommonApi.class);
        verify(gate).hasAnyPermissions(9L, PERMISSION);
        verifyNoMoreInteractions(gate);
    }

    @Test
    void auxiliaryCategoriesAndPageFiltersNeedNoLegacyManagementPermission() throws Exception {
        var response = request(get("/admin-api/asset/market-category/simple-list"));
        assertThat(response.get("code").intValue()).isZero();
        assertThat(response.get("data").get(0).get("id").longValue()).isEqualTo(2L);
        assertThat(request(get(PREFIX + "/page").param("keyword", "冻结目录").param("deliveryType", "1")
                .param("marketCategoryId", "2").param("pageNo", "2").param("pageSize", "1")).get("code").intValue()).isZero();
        verify(mine).selectOwnedPage(eq(9L), argThat(req -> req.getKeyword().equals("冻结目录") && req.getDeliveryType() == 1
                && req.getMarketCategoryId() == 2L && req.getPageNo() == 2 && req.getPageSize() == 1), eq(Set.of(2L)), eq(1L));
        verifyNoInteractions(dataApi, supplyApi, quotaApi);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "get", "preview", "api-document"})
    void legacyMarketQueryPermissionCannotReplaceIndependentPermission(String path) throws Exception {
        login(20L);
        assertThat(request(get(PREFIX + "/" + path).param("id", String.valueOf(TABLE_GRANT))).get("code").intValue()).isEqualTo(403);
        verifyNoInteractions(mine, dataApi, supplyApi, quotaApi);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "get", "preview", "api-document"})
    void anonymousHttpPrincipalCannotReachAnyDomainRoute(String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("http-fixture", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(request(get(PREFIX + "/" + path).param("id", String.valueOf(TABLE_GRANT))).get("code").intValue()).isEqualTo(403);
        verifyNoInteractions(mine, dataApi, supplyApi, quotaApi);
    }

    @Test
    void foreignGrantCannotBeReadOrConsumedThroughHttpEvenWithTheMenuPermission() throws Exception {
        for (String path : List.of("get", "preview", "api-document")) {
            assertThat(request(get(PREFIX + "/" + path).param("id", "99")).get("code").intValue()).isEqualTo(MARKET_APPLY_NOT_AUTHORIZED.getCode());
        }
        verifyNoInteractions(dataApi, supplyApi, quotaApi);
    }

    @Test
    void expiredHistoryIsReadableWhilePreviewRejectsBeforeDataAndQuotaRpc() throws Exception {
        table.setApplyEndTime(NOW);
        var history = request(get(PREFIX + "/get").param("id", String.valueOf(TABLE_GRANT)));
        assertThat(history.get("data").get("accessStatus").asString()).isEqualTo("EXPIRED");
        assertThat(request(get(PREFIX + "/preview").param("id", String.valueOf(TABLE_GRANT))).get("code").intValue()).isEqualTo(MARKET_APPLY_NOT_AUTHORIZED.getCode());
        verifyNoInteractions(dataApi, supplyApi, quotaApi);
    }

    @Test
    void previewBindsDefaultLimitAndConsumptionHeaderAndReturnsOnlyApprovedFields() throws Exception {
        var response = request(get(PREFIX + "/preview").param("id", String.valueOf(TABLE_GRANT))
                .header("X-Consumption-Key", "http-request-0001"));
        assertThat(response.get("code").intValue()).isZero();
        assertThat(response.get("data").get("columns").size()).isEqualTo(1);
        assertThat(response.get("data").get("columns").get(0).get("name").asString()).isEqualTo("Id");
        assertThat(response.toString()).doesNotContain("Secret", "must-not-leak", "test-reservation");
        verify(quotaApi).reserve(argThat(reserve -> reserve.requestKey().equals("http-request-0001") && reserve.actorId() == 9
                && reserve.rowLimit() == 20 && reserve.columns().equals(List.of("Id"))));
        verify(dataApi).previewTable(argThat(req -> req.getLimit() == 1 && req.getColumns().equals(List.of("Id"))));
        verify(quotaApi).settle(new SupplyQuotaDTO.Settle("PORTAL:9:5", "http-request-0001", "test-reservation", 1, false));
    }

    @Test
    void apiDocumentHasOnlyApprovedFieldsAndRejectsChangedPublishedContract() throws Exception {
        var response = request(get(PREFIX + "/api-document").param("id", "8"));
        assertThat(response.get("code").intValue()).isZero();
        assertThat(response.get("data").get("responseFields").size()).isEqualTo(1);
        assertThat(response.get("data").get("responseFields").get(0).get("name").asString()).isEqualTo("publicId");
        assertThat(response.toString()).doesNotContain("Secret", "databaseId", "outputFieldMapping", "contractHash");
        document.setContractHash("b".repeat(64));
        assertThat(request(get(PREFIX + "/api-document").param("id", "8")).get("code").intValue()).isEqualTo(MARKET_APPLY_SCOPE_UNCONFIRMED.getCode());
        verifyNoInteractions(dataApi, quotaApi);
    }

    @Test
    void invalidRequestShapeAndWrongHttpVerbCannotReachBusinessMappers() throws Exception {
        assertThat(request(get(PREFIX + "/get")).get("code").intValue()).isEqualTo(400);
        assertThat(request(get(PREFIX + "/get").param("id", "not-an-id")).get("code").intValue()).isEqualTo(400);
        for (String value : List.of("0", "-1")) {
            assertThat(request(get(PREFIX + "/get").param("id", value)).get("code").intValue()).isEqualTo(400);
            assertThat(request(get(PREFIX + "/page").param("pageNo", value)).get("code").intValue()).isEqualTo(400);
            assertThat(request(get(PREFIX + "/page").param("deliveryType", value)).get("code").intValue()).isEqualTo(400);
        }
        assertThat(request(get(PREFIX + "/page").param("pageSize", "201")).get("code").intValue()).isEqualTo(400);
        assertThat(request(get(PREFIX + "/preview").param("id", String.valueOf(TABLE_GRANT)).param("limit", "101")).get("code").intValue()).isEqualTo(400);
        assertThat(request(post(PREFIX + "/get").param("id", String.valueOf(TABLE_GRANT))).get("code").intValue()).isEqualTo(405);
        verifyNoInteractions(mine, dataApi, supplyApi, quotaApi);
    }

    private JsonNode request(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void configureConfirmedApiDelivery(MarketDeliveryService delivery) {
        String operationId = "asset:asset:apply:8:r2:GRANT";
        var command = delivery.buildCommand(api, SupplyGrantSourceCommandDTO.Action.GRANT, operationId, 2L);
        var operation = MarketDeliveryOperationDO.builder().id(1L).applyId(8L).operationId(operationId)
                .sourceGrantId("apply:8:r1").appId(22L).apiId(21L).opType("GRANT").expectedRevision(1).sourceRevision(2L)
                .protocolVersion(2).status("SUCCESS").resolutionStatus("CONFIRMED")
                .payloadJson(MarketDeliveryPayload.toJson(command)).payloadHash(MarketDeliveryPayload.hash(command)).build();
        var approval = new MarketApprovalEvidenceRow(); approval.setId(1L); approval.setApplyId(8L); approval.setApprovalRef("apply:8:r1");
        approval.setApprovalAssetId(6L); approval.setApprovalScopeHash(MarketDeliveryPayload.approvalHash(6L, command));
        when(operations.selectLatestByApply(8L)).thenReturn(operation);
        when(operations.selectLatestByApplies(anyCollection())).thenReturn(List.of(operation));
        when(approvals.selectByApply(8L)).thenReturn(approval);
        when(approvals.selectByApplies(anyCollection())).thenReturn(List.of(approval));
    }

    private void configureMetadata(DataAssetDO asset) {
        var metadata = new MetadataTableRespDTO(); metadata.setId(asset.getMetaTableId()); metadata.setResourceUrn(asset.getResourceUrn());
        metadata.setObjectStatus(1); metadata.setDatasourceId(4L); metadata.setDbName("hospital"); metadata.setSchemaName("public"); metadata.setTableName(asset.getTableName());
        var column = new MetadataColumnRespDTO(); column.setId(asset.getMetaTableId() + 100); column.setTableId(asset.getMetaTableId());
        column.setColumnName("Id"); column.setObjectStatus(1); column.setResourceUrn("urn:column:" + column.getId());
        when(metadataApi.getTable(asset.getMetaTableId())).thenReturn(CommonResult.success(metadata));
        when(metadataApi.getColumnListBySnapshot(asset.getPublishedSnapshotId())).thenReturn(CommonResult.success(List.of(column)));
        when(metadataApi.getColumnList(asset.getMetaTableId())).thenReturn(CommonResult.success(List.of(column)));
    }

    private static MarketMyAssetRow row(long id, long assetId, boolean api) {
        var data = MarketResourceApplyDO.builder().id(id).assetId(assetId).assetType(1).applyUserId(9L).status(2).ifAuth(1)
                .revision(1).sourceRevision(2L).paused(0).catalogVersionId(assetId + 20).applyUseType(api ? 0 : 2)
                .applyUseTimes(api ? 0 : 5).appId(api ? 22L : null).apiId(api ? 21L : null).sourceGrantId(api ? "apply:8:r1" : null).build();
        var evidence = new java.util.LinkedHashMap<String, Object>();
        evidence.put("assetId", String.valueOf(assetId)); evidence.put("assetVersion", "v1"); evidence.put("catalogVersionId", String.valueOf(assetId + 20));
        evidence.put("publishedSnapshotId", String.valueOf(assetId + 10)); evidence.put("databaseId", "4"); evidence.put("schemaName", "public");
        evidence.put("tableName", "citizen" + assetId); evidence.put("frozenColumns", List.of("Id"));
        evidence.put("appId", api ? "22" : ""); evidence.put("apiId", api ? "21" : ""); evidence.put("dutyMatter", "");
        evidence.put("applyUseType", api ? "0" : "2"); evidence.put("applyUseTimes", api ? "0" : "5");
        if (api) { evidence.put("approvedContractHash", "a".repeat(64)); evidence.put("apiVersion", "v1"); evidence.put("activeReleaseId", "31"); }
        data.setSnapshot(JsonUtils.toJsonString(evidence)); data.setCreateTime(NOW.minusDays(1));
        var row = BeanUtils.toBean(data, MarketMyAssetRow.class); row.setFrozenCatalogValid(true); row.setAssetName("冻结目录");
        row.setMarketName("正式集市资源"); row.setDeliveryType(api ? 2 : 1); return row;
    }

    private static DataAssetDO asset(long id) {
        return DataAssetDO.builder().id(id).assetType(1).status(1).metaTableId(id + 100).resourceUrn("urn:table:" + id)
                .datasourceId(4L).dbName("hospital").schemaName("public").tableName("citizen" + id).publishedVersion("v1")
                .publishedSnapshotId(id + 10).publishedCatalogVersionId(id + 20).build();
    }

    private static SupplyPublishedApiDocumentRespDTO document() {
        var doc = new SupplyPublishedApiDocumentRespDTO(); doc.setId(21L); doc.setName("公开API"); doc.setMethod("GET"); doc.setPath("/api/approved");
        doc.setVersion("v1"); doc.setContractHash("a".repeat(64)); doc.setActiveReleaseId(31L); doc.setDatabaseId(4L);
        doc.setSchemaName("public"); doc.setTableName("citizen6"); doc.setFieldMappingVerified(true);
        doc.setOutputFieldMapping(Map.of("publicId", "Id", "privateData", "Secret"));
        var allowed = new SupplyPublishedApiDocumentRespDTO.RequestParam(); allowed.setName("Id"); allowed.setType("VARCHAR");
        var hidden = new SupplyPublishedApiDocumentRespDTO.RequestParam(); hidden.setName("Secret");
        doc.setRequestParams(List.of(allowed, hidden));
        var output = new SupplyPublishedApiDocumentRespDTO.ResponseField(); output.setName("publicId"); output.setType("VARCHAR");
        var hiddenOutput = new SupplyPublishedApiDocumentRespDTO.ResponseField(); hiddenOutput.setName("privateData");
        doc.setResponseFields(List.of(output, hiddenOutput)); return doc;
    }

    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(id), null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class SecurityConfig {
        @Bean static MethodValidationPostProcessor methodValidation() {
            var processor = new MethodValidationPostProcessor(); processor.setProxyTargetClass(true); return processor;
        }
        @Bean PermissionCommonApi permissionApi() { return mock(PermissionCommonApi.class); }
        @Bean("ss") SecurityFrameworkService security(PermissionCommonApi api) { return new SecurityFrameworkServiceImpl(api); }
        @Bean MarketMyAssetService unusedService() { return mock(MarketMyAssetService.class); }
        @Bean MarketCategoryService unusedCategory() { return mock(MarketCategoryService.class); }
        @Bean MarketMyAssetController controller() { return new MarketMyAssetController(); }
        @Bean MarketCategoryController categoryController() { return new MarketCategoryController(); }
    }
}
