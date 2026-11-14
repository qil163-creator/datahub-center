package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门户即席查询：批准字段作为执行前约束交给 DataForge，返回后过滤只是防御，不能替代它。
 */
class DataQueryFieldScopeTest {

    private DataQueryServiceImpl service;
    private DatabaseApi databaseApi;
    private MarketAccessService marketAccessService;
    private MarketResourceApplyDO selectedApply;
    private org.mockito.MockedStatic<cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils> security;

    @org.junit.jupiter.api.AfterEach
    void clearSecurity() {
        security.close();
    }

    private static DataQueryExecuteReqVO request(String sql) {
        DataQueryExecuteReqVO vo = new DataQueryExecuteReqVO();
        vo.setDatasourceId(3L);
        vo.setSql(sql);
        vo.setLimit(100);
        return vo;
    }

    private static MarketResourceApplyDO approvedApply(String frozen) {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(7L);
        apply.setSnapshot(frozen);
        return apply;
    }

    @BeforeEach
    void setUp() {
        security = org.mockito.Mockito.mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class);
        security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
        service = new DataQueryServiceImpl();
        databaseApi = mock(DatabaseApi.class);
        marketAccessService = mock(MarketAccessService.class);
        ReflectionTestUtils.setField(service, "databaseApi", databaseApi);
        ReflectionTestUtils.setField(service, "marketAccessService", marketAccessService);
        ReflectionTestUtils.setField(service, "assetAccessGuard", mock(AssetAccessGuard.class));
        ReflectionTestUtils.setField(service, "safetySecurityApi",
                mock(cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi.class));

        var table = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO();
        table.setSchemaName("public");
        table.setTableName("citizen");
        table.setTableReference("\"public\".\"citizen\"");
        table.setNormalizedSql("SELECT * FROM " + table.getTableReference());
        when(databaseApi.resolveQueryTable(any(), any())).thenReturn(CommonResult.success(table));
        RestrictedSelectRespDTO plan = new RestrictedSelectRespDTO();
        plan.setTableName("citizen");
        plan.setSchemaName("public");
        plan.setNormalizedSql("SELECT * FROM \"public\".\"citizen\"");
        plan.setWildcard(true);
        plan.setSourceColumns(List.of());
        plan.setRelationObjectId("a".repeat(64));
        when(databaseApi.parseRestrictedSelect(any())).thenReturn(CommonResult.success(plan));
        when(databaseApi.executeQuery(any())).thenReturn(CommonResult.success(new DatabasePreviewRespDTO()));
        when(marketAccessService.filterPreview(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(marketAccessService.requireQuerySnapshot(any(), anyLong())).thenAnswer(call ->
                new cn.iocoder.yudao.module.asset.service.market.MarketQuerySnapshot( 9L, 7L, 5L,
                        List.of(new cn.iocoder.yudao.module.asset.service.market.MarketQuerySnapshot.Grant(
                                7L, 2L, 1, selectedApply.getSnapshot(), null, null, null)),
                        cn.iocoder.yudao.module.asset.service.market.MarketFieldScope.effectiveScope(selectedApply),
                        3L, "public", "citizen", java.time.LocalDateTime.now()));
    }

    @Test
    void approvedColumnsAreSentAsExecutionConstraints() {
        selectedApply = approvedApply("{\"frozenColumns\":[\"id\",\"phone\"]}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any()))
                .thenReturn(selectedApply);
        service.executeQuery(request("select * from citizen"));
        ArgumentCaptor<DatabaseQueryReqDTO> captor = ArgumentCaptor.forClass(DatabaseQueryReqDTO.class);
        verify(databaseApi).executeQuery(captor.capture());
        assertThat(captor.getValue().getExpectedTable()).isEqualTo("citizen");
        assertThat(captor.getValue().getExpectedSchema()).isEqualTo("public");
        assertThat(captor.getValue().getExpectedObjectId()).isEqualTo("a".repeat(64));
        assertThat(captor.getValue().getSql()).isEqualTo("SELECT * FROM \"public\".\"citizen\"");
        assertThat(Set.copyOf(captor.getValue().getAllowedColumns())).containsExactlyInAnyOrder("id", "phone");
        verify(marketAccessService).assertStillCurrent(any());
    }

    @Test
    void applyWithoutFrozenEvidenceCannotQueryAtAll() {
        selectedApply = approvedApply("{}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any()))
                .thenReturn(approvedApply("{}"));
        assertThatThrownBy(() -> service.executeQuery(request("select * from citizen")))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_026_024));
        verify(databaseApi, never()).executeQuery(any());
    }

    @Test
    void nonMarketQueryKeepsItsExistingUnscopedContract() {
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any())).thenReturn(null);
        service.executeQuery(request("select * from citizen"));
        ArgumentCaptor<DatabaseQueryReqDTO> captor = ArgumentCaptor.forClass(DatabaseQueryReqDTO.class);
        verify(databaseApi).executeQuery(captor.capture());
        assertThat(captor.getValue().getExpectedTable()).isNull();
        assertThat(captor.getValue().getAllowedColumns()).isNull();
    }

    @Test
    void missingFrozenScopeIsRejectedBeforeColumnMetadataRpc() {
        selectedApply = approvedApply("{}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any()))
                .thenReturn(approvedApply("{}"));
        assertThatThrownBy(() -> service.getColumnList(3L, "citizen")).isInstanceOf(ServiceException.class);
        verify(databaseApi, never()).getQueryTableColumns(any());
    }

    @Test
    void queryMustDiscardResultWhenFinalAuthorizationReadRejectsIt() {
        selectedApply = approvedApply("{\"frozenColumns\":[\"id\"]}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any())).thenReturn(selectedApply);
        org.mockito.Mockito.doThrow(new ServiceException(1_500_026_028, "changed"))
                .when(marketAccessService).assertStillCurrent(any());
        assertThatThrownBy(() -> service.executeQuery(request("select * from citizen")))
                .isInstanceOf(ServiceException.class);
        verify(databaseApi).executeQuery(any());
    }

    @Test
    void columnListMustAlsoRevalidateBeforeReturning() {
        selectedApply = approvedApply("{\"frozenColumns\":[\"id\"]}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any())).thenReturn(selectedApply);
        when(databaseApi.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of()));
        org.mockito.Mockito.doThrow(new ServiceException(1_500_026_028, "changed"))
                .when(marketAccessService).assertStillCurrent(any());
        assertThatThrownBy(() -> service.getColumnList(3L, "citizen")).isInstanceOf(ServiceException.class);
        verify(databaseApi).getQueryTableColumns(any());
    }

    @Test
    void oldProviderWithoutBoundSqlCannotFallBackToUserSql() {
        var oldPlan = new RestrictedSelectRespDTO();
        oldPlan.setTableName("citizen");
        oldPlan.setSchemaName("public");
        when(databaseApi.parseRestrictedSelect(any())).thenReturn(CommonResult.success(oldPlan));
        assertThatThrownBy(() -> service.executeQuery(request("select * from citizen")))
                .isInstanceOf(ServiceException.class);
        verify(databaseApi, never()).executeQuery(any());
    }

    @Test
    void aReboundAssetCannotChangeTheAuthorizedQueryTarget() {
        selectedApply = approvedApply("{\"frozenColumns\":[\"id\"]}");
        when(marketAccessService.findEffectiveApplyForTable(any(), anyLong(), any(), any())).thenReturn(selectedApply);
        var changedPlan = new RestrictedSelectRespDTO();
        changedPlan.setTableName("citizen");
        changedPlan.setSchemaName("other");
        changedPlan.setWildcard(true);
        changedPlan.setNormalizedSql("SELECT * FROM \"other\".\"citizen\"");
        when(databaseApi.parseRestrictedSelect(any())).thenReturn(CommonResult.success(changedPlan));
        assertThatThrownBy(() -> service.executeQuery(request("select * from other.citizen")))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
        verify(databaseApi, never()).executeQuery(any());
    }
}
