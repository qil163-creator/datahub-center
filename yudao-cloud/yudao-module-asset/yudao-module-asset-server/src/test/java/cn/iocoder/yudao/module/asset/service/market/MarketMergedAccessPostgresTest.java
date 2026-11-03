package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryServiceImpl;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实际 PostgreSQL 的来源集合、隔离事务和动态撤权；远端数据 API 使用有界可控替身。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketMergedAccessPostgresTest extends SharingPostgresSupport {
    private MarketAccessServiceImpl service;
    private MarketResourceApplyMapper mapper;
    private DataAssetMapper assets;
    private DataAssetDO asset;
    private DataSourceTransactionManager transactions;
    private DatabaseApi database;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,snapshot)
                  VALUES (7,5,9,2,1,0,1,10,
                  '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}');
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,snapshot)
                  SELECT 8,5,9,2,1,0,1,20,replace(snapshot,'"Id"','"Phone"') FROM dh_market_resource_apply WHERE id=7;
                """);
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", jdbcUser, "");
        transactions = new DataSourceTransactionManager(datasource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("merged-query", new SpringManagedTransactionFactory(), datasource));
        config.addMapper(MarketResourceApplyMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(MarketResourceApplyMapper.class);
        asset = DataAssetDO.builder().id(5L).status(DataAssetDO.STATUS_PUBLISHED)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE).publishedVersion("v1")
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build();
        assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset));
        when(assets.selectExactTableBindings(eq(4L), anyString(), anyString())).thenCallRealMethod();
        service = new MarketAccessServiceImpl();
        ReflectionTestUtils.setField(service, "metadataIdentityGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard.class));
        ReflectionTestUtils.setField(service, "applyMapper", mapper);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        database = mock(DatabaseApi.class);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "assetMonitorService", mock(AssetMonitorService.class));
        TenantContextHolder.clear();
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
    }

    @AfterEach
    void clearContext() {
        if (security != null) security.close();
        TenantContextHolder.clear();
    }

    @Test
    void compatibleApplicationsProduceAnImmutablePhysicalUnion() {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        assertThat(snapshot.fieldScope().getColumns()).containsExactly("Id", "Phone");
        assertThatThrownBy(() -> snapshot.fieldScope().getColumns().add("secret"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatCode(() -> service.assertStillCurrent(snapshot)).doesNotThrowAnyException();
    }

    @Test
    void fullIdentityDisambiguatesSameTableNamesWithoutBorrowingAnotherGrant() {
        var other = DataAssetDO.builder().id(6L).schemaName("other").tableName("citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, other));
        assertThat(service.findEffectiveApplyForTable(9L, 4L, "public", "citizen").getAssetId()).isEqualTo(5L);
        assertThat(service.findEffectiveApplyForTable(9L, 4L, "other", "citizen")).isNull();
    }

    @Test
    void physicalCaseIsNotFoldedAgainDuringGrantLookup() {
        var other = DataAssetDO.builder().id(6L).schemaName("public").tableName("Citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, other));
        assertThat(service.findEffectiveApplyForTable(9L, 4L, "public", "citizen").getAssetId()).isEqualTo(5L);
        assertThat(service.findEffectiveApplyForTable(9L, 4L, "public", "Citizen")).isNull();
    }

    @Test
    void duplicateIdentityOrMissingLegacySchemaCannotBecomeUnscoped() {
        var other = DataAssetDO.builder().id(6L).schemaName("public").tableName("citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, other));
        assertThatThrownBy(() -> service.findEffectiveApplyForTable(9L, 4L, "public", "citizen"))
                .isInstanceOf(ServiceException.class);
        other.setSchemaName(null);
        assertThatThrownBy(() -> service.findEffectiveApplyForTable(9L, 4L, "public", "citizen"))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=6,if_auth=0", "paused=1", "if_auth=0", "deleted=1",
            "apply_end_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai' - interval '1 second'"})
    void withdrawingOneSourceRejectsOldResultAndRemovesOnlyItsFields(String change) throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=8");
        assertChanged(snapshot);
        assertThat(service.requireQuerySnapshot(9L, 7L).fieldScope().getColumns()).containsExactly("Id");
    }

    @Test
    void revokingAnchorStillLeavesOtherSourceUsableOnANewRequest() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=7");
        assertChanged(snapshot);
        assertThat(service.requireQuerySnapshot(9L, 8L).fieldScope().getColumns()).containsExactly("Phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"purpose='different'", "duty_matter='different'", "legal_basis='different'",
            "app_id=2,api_id=3", "apply_use_type=1", "apply_use_times=10", "source_revision=NULL", "snapshot='{}'",
            "snapshot=replace(snapshot,'public','private')", "snapshot=replace(snapshot,'v1','v2')"})
    void incompatibleOrUnprovableEffectiveSourceCannotBeSilentlyIgnored(String change) throws Exception {
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=8");
        assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.hasEffectiveAccess(9L, 5L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply_user_id=10", "asset_id=6"})
    void differentTenantUserOrAssetNeverContributesFields(String change) throws Exception {
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=8");
        assertThat(service.requireQuerySnapshot(9L, 7L).fieldScope().getColumns()).containsExactly("Id");
    }

    @Test
    void futureIntervalsAreNotFlattenedIntoOneContinuousGrant() throws Exception {
        sql("UPDATE dh_market_resource_apply SET apply_start_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai' + interval '1 day' WHERE id=8");
        assertThat(service.requireQuerySnapshot(9L, 7L).fieldScope().getColumns()).containsExactly("Id");
        sql("UPDATE dh_market_resource_apply SET apply_end_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai' - interval '1 second' WHERE id=7");
        assertThat(service.findEffectiveApply(9L, 5L)).isNull();
        assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 8L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void unchangedUnionDoesNotHideSourceRevisionAba() throws Exception {
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET source_revision=source_revision+2 WHERE id=8");
        assertChanged(snapshot);
    }

    @Test
    void newlyEffectiveSourceCannotBeAddedToAnInFlightResult() throws Exception {
        sql("UPDATE dh_market_resource_apply SET paused=1 WHERE id=8");
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        sql("UPDATE dh_market_resource_apply SET paused=0,source_revision=21 WHERE id=8");
        assertChanged(snapshot);
    }

    @Test
    void mergedPreviewConstrainBeforeRpcAndRechecksEverySourceBeforeRelease() throws Exception {
        when(database.previewTable(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            DatabasePreviewReqDTO request = call.getArgument(0);
            assertThat(request.getColumns()).containsExactly("Id", "Phone");
            // 与读取事务不同的真实连接，在数据 RPC 内提交另一来源的撤销。
            sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0,source_revision=11 WHERE id=7");
            return result();
        });
        assertThatThrownBy(() -> service.previewAuthorizedAsset(5L, 20)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE first_used_time IS NOT NULL")).isZero();
    }

    @Test
    void newReadTransactionSeesSecondaryRevocationDespiteOldCallerSnapshot() {
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(mapper.selectById(8L).getStatus()).isEqualTo(2);
            var snapshot = service.requireQuerySnapshot(9L, 7L);
            try {
                sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=8");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            assertChanged(snapshot);
        });
    }

    @Test
    void excessiveSourcesFailClosedInsteadOfSilentlyTruncatingTheUnion() throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,snapshot)
                  SELECT n,5,9,2,1,0,1,10,a.snapshot
                  FROM generate_series(100,354) n CROSS JOIN dh_market_resource_apply a WHERE a.id=7
                """);
        assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
        sql("UPDATE dh_market_resource_apply SET if_auth=0 WHERE id=8");
        assertThat(service.requireQuerySnapshot(9L, 7L).grants()).hasSize(256);
    }

    @Test
    void ambiguousSchemaAndCaseOnlyTableMatchesAreNotSelectedByFirstMatch() {
        var other = DataAssetDO.builder().id(6L).datasourceId(4L).schemaName("private").tableName("citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, other));
        assertThatThrownBy(() -> service.findEffectiveApplyForTable(9L, 4L, "citizen"))
                .isInstanceOf(ServiceException.class);
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset));
        assertThatThrownBy(() -> service.findEffectiveApplyForTable(9L, 4L, "Citizen"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void compatibleApiContractsAndTrimmedPurposeMergeButChangedContractDoesNot() throws Exception {
        sql("""
                UPDATE dh_market_resource_apply SET app_id=2,api_id=3,purpose=' task ',
                  snapshot=(snapshot::jsonb || '{"appId":"2","apiId":"3","approvedContractHash":"hash","apiVersion":"v1"}'::jsonb)::text;
                UPDATE dh_market_resource_apply SET purpose='task' WHERE id=8;
                """);
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        assertThat(snapshot.fieldScope().getColumns()).containsExactly("Id", "Phone");
        assertThatThrownBy(() -> snapshot.grants().clear()).isInstanceOf(UnsupportedOperationException.class);
        sql("UPDATE dh_market_resource_apply SET snapshot=replace(snapshot,'hash','other') WHERE id=8");
        assertChanged(snapshot);
        assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void queryConsumerSendsMergedColumnsAndRecomputesAfterOneRevocation() throws Exception {
        var query = queryService();
        when(database.executeQuery(any())).thenAnswer(call -> {
            DatabaseQueryReqDTO request = call.getArgument(0);
            assertThat(request.getAllowedColumns()).containsExactly("Id", "Phone");
            assertThat(request.getExpectedSchema()).isEqualTo("public");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result();
        });
        assertThat(query.executeQuery(queryRequest()).getColumns()).extracting("name").containsExactly("Id", "Phone");
        sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=8");
        doAnswer(call -> {
            DatabaseQueryReqDTO request = call.getArgument(0);
            assertThat(request.getAllowedColumns()).containsExactly("Id");
            return result();
        }).when(database).executeQuery(any());
        assertThat(query.executeQuery(queryRequest()).getColumns()).extracting("name").containsExactly("Id");
    }

    @Test
    void queryAndColumnConsumersRevalidateSecondarySourceBeforeReturning() throws Exception {
        var query = queryService();
        when(database.executeQuery(any())).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET source_revision=source_revision+2 WHERE id=7");
            return result();
        });
        assertThatThrownBy(() -> query.executeQuery(queryRequest())).isInstanceOf(ServiceException.class);
        when(database.getQueryTableColumns(any())).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET if_auth=0 WHERE id=7");
            return CommonResult.success(List.of(column("Id"), column("Phone")));
        });
        assertThatThrownBy(() -> query.getColumnList(4L, "citizen")).isInstanceOf(ServiceException.class);
    }

    @Test
    void columnConsumerFiltersUsingBothSourcesAndPortalMarksAllContributorsUsed() throws Exception {
        var query = queryService();
        when(database.getQueryTableColumns(any())).thenAnswer(call -> {
            DatabaseQueryColumnsReqDTO request = call.getArgument(0);
            assertThat(request.getSchemaName()).isEqualTo("public");
            assertThat(request.getTableName()).isEqualTo("citizen");
            assertThat(request.getColumns()).containsExactly("Id", "Phone");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return CommonResult.success(List.of(column("Id"), column("Phone"), column("secret")));
        });
        assertThat(query.getColumnList(4L, "citizen")).extracting("columnName").containsExactly("Id", "Phone");
        when(database.previewTable(any())).thenReturn(result());
        assertThat(service.previewAuthorizedAsset(5L, 20).getRows()).containsExactly(Map.of("Id", 1, "Phone", "test"));
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE first_used_time IS NOT NULL")).isEqualTo(2);
    }

    @Test
    void tableListUsesTheCompleteSourceProofWithoutReadingDataOrConsumingAUse() throws Exception {
        var query = queryService();
        assertThat(query.getTableList(4L)).containsExactly("\"public\".\"citizen\"");
        verify(database, never()).getQueryTableColumns(any());
        verify(database, never()).executeQuery(any());
        verify(database, never()).getTableNameList(any());
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE first_used_time IS NOT NULL")).isZero();
    }

    @Test
    void incompatibleSecondarySourceCannotProduceAPartialTableList() throws Exception {
        var query = queryService();
        sql("UPDATE dh_market_resource_apply SET purpose='incompatible' WHERE id=8");
        assertThatThrownBy(() -> query.getTableList(4L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void tableListRechecksSecondarySourceAtTheFinalReturnBoundary() throws Exception {
        var query = queryService();
        var racing = spy(service);
        ReflectionTestUtils.setField(query, "marketAccessService", racing);
        doAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET if_auth=0,source_revision=source_revision+1 WHERE id=8");
            return call.callRealMethod();
        }).when(racing).assertStillCurrent(any());
        assertThatThrownBy(() -> query.getTableList(4L)).isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode())
                        .isEqualTo(cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED.getCode()));
        verify(racing).assertStillCurrent(any());
        assertThat(queryService().getTableList(4L)).containsExactly("\"public\".\"citizen\"");
    }

    @Test
    void catalogPreviewUsesTheSameUnionAndRejectsSecondaryRevocation() {
        var catalog = new cn.iocoder.yudao.module.asset.service.dataasset.DataAssetServiceImpl();
        ReflectionTestUtils.setField(catalog, "assetMapper", assets);
        ReflectionTestUtils.setField(catalog, "catalogService",
                mock(cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService.class));
        ReflectionTestUtils.setField(catalog, "assetAccessGuard", mock(AssetAccessGuard.class));
        ReflectionTestUtils.setField(catalog, "marketAccessService", service);
        ReflectionTestUtils.setField(catalog, "etlBatchBindings", mock(cn.iocoder.yudao.module.asset.service.dataasset.AssetEtlBatchBindingService.class));
        ReflectionTestUtils.setField(catalog, "databaseApi", database);
        ReflectionTestUtils.setField(catalog, "monitorService", mock(AssetMonitorService.class));
        var request = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO();
        request.setId(5L);
        when(database.previewTable(any())).thenReturn(result());
        assertThat(service.hasEffectiveAccess(9L, 5L)).isTrue();
        assertThat(catalog.previewAsset(request).getColumns()).extracting("name").containsExactly("Id", "Phone");
        doAnswer(call -> {
            DatabasePreviewReqDTO dataRequest = call.getArgument(0);
            assertThat(dataRequest.getColumns()).containsExactly("Id", "Phone");
            sql("UPDATE dh_market_resource_apply SET paused=1,source_revision=11 WHERE id=7");
            return result();
        }).when(database).previewTable(any());
        assertThatThrownBy(() -> catalog.previewAsset(request)).isInstanceOf(ServiceException.class);
    }

    private DataQueryServiceImpl queryService() {
        var query = new DataQueryServiceImpl();
        ReflectionTestUtils.setField(query, "marketAccessService", service);
        ReflectionTestUtils.setField(query, "databaseApi", database);
        var guard = mock(AssetAccessGuard.class);
        when(guard.canReadPublishedTable(any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(query, "assetAccessGuard", guard);
        ReflectionTestUtils.setField(query, "safetySecurityApi", mock(SafetySecurityApi.class));

        var plan = new RestrictedSelectRespDTO();
        plan.setTableName("citizen");
        plan.setSchemaName("public");
        plan.setNormalizedSql("SELECT * FROM \"public\".\"citizen\"");
        plan.setWildcard(true);
        when(database.parseRestrictedSelect(any())).thenReturn(CommonResult.success(plan));
        var table = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO();
        table.setSchemaName("public");
        table.setTableName("citizen");
        table.setTableReference("\"public\".\"citizen\"");
        table.setNormalizedSql("SELECT * FROM " + table.getTableReference());
        when(database.resolveQueryTable(any(), any())).thenReturn(CommonResult.success(table));
        when(database.getQueryTableList(4L)).thenReturn(CommonResult.success(List.of(table)));
        return query;
    }

    @Test
    void identicalFieldUnionStillTracksEverySourceIdentity() throws Exception {
        sql("UPDATE dh_market_resource_apply SET snapshot=replace(snapshot,'Phone','Id') WHERE id=8");
        var snapshot = service.requireQuerySnapshot(9L, 7L);
        assertThat(snapshot.fieldScope().getColumns()).containsExactly("Id");
        sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0 WHERE id=8");
        assertChanged(snapshot);
        assertThat(service.requireQuerySnapshot(9L, 7L).fieldScope().getColumns()).containsExactly("Id");
    }

    private static DataQueryExecuteReqVO queryRequest() {
        var request = new DataQueryExecuteReqVO();
        request.setDatasourceId(4L);
        request.setSql("SELECT * FROM public.citizen");
        return request;
    }

    private static DatabaseColumnRespDTO column(String name) {
        var column = new DatabaseColumnRespDTO();
        column.setColumnName(name);
        return column;
    }

    private void assertChanged(MarketQuerySnapshot snapshot) {
        assertThatThrownBy(() -> service.assertStillCurrent(snapshot)).isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
    }

    private static CommonResult<DatabasePreviewRespDTO> result() {
        var result = new DatabasePreviewRespDTO();
        result.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint"),
                new DatabasePreviewRespDTO.Column("Phone", "联系方式", "varchar")));
        result.setRows(List.of(Map.of("Id", 1, "Phone", "test")));
        return CommonResult.success(result);
    }
}
