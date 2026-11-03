package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际 AssetAccessGuard + 查询服务，证明初次检查通过不能代替执行时的独立授权依据。 */
class DataQueryAuthorizationRaceTest {
    private DataQueryServiceImpl service;
    private AssetAccessGuard guard;
    private DataAssetDO asset;
    private DatabaseApi database;
    private MarketResourceApplyMapper applies;
    private DataAssetMapper assets;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
        asset = DataAssetDO.builder().id(5L).status(DataAssetDO.STATUS_PUBLISHED)
                .schemaName("public").tableName("citizen").build();
        assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset));
        when(assets.selectExactTableBindings(eq(4L), anyString(), anyString())).thenCallRealMethod();
        applies = mock(MarketResourceApplyMapper.class);
        when(applies.databaseNow()).thenReturn(LocalDateTime.of(2040, 1, 1, 0, 0));
        when(applies.selectEffective(eq(9L), eq(5L), any())).thenReturn(new MarketResourceApplyDO());
        var permissions = mock(PermissionApi.class);
        when(permissions.hasAnyRoles(anyLong(), any())).thenReturn(CommonResult.success(false));
        when(permissions.hasAnyPermissions(anyLong(), any(), any())).thenReturn(CommonResult.success(false));
        guard = new AssetAccessGuard();
        ReflectionTestUtils.setField(guard, "metadataIdentityGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard.class));
        ReflectionTestUtils.setField(guard, "assetMapper", assets);
        ReflectionTestUtils.setField(guard, "applyMapper", applies);
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        service = new DataQueryServiceImpl();
        ReflectionTestUtils.setField(service, "assetAccessGuard", guard);
        ReflectionTestUtils.setField(service, "marketAccessService", mock(MarketAccessService.class));
        database = mock(DatabaseApi.class);
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
        when(database.executeQuery(any())).thenReturn(CommonResult.success(new DatabasePreviewRespDTO()));
        ReflectionTestUtils.setField(service, "databaseApi", database);

        ReflectionTestUtils.setField(service, "safetySecurityApi", mock(SafetySecurityApi.class));
    }

    @AfterEach
    void clearContext() {
        security.close();
    }

    @Test
    void disappearingGrantCannotFallBackToUnscopedSql() {
        assertThatThrownBy(() -> service.executeQuery(request())).isInstanceOf(ServiceException.class);
        verify(database, never()).executeQuery(any());
        verify(applies).selectEffective(9L, 5L, LocalDateTime.of(2040, 1, 1, 0, 0));
    }

    @Test
    void exactTableListGuardHidesOnlyAnExplicitPermissionDenial() {
        assertThat(guard.canReadPublishedTable(4L, "public", "citizen", 9L)).isTrue();
        when(applies.selectEffective(eq(9L), eq(5L), any())).thenReturn(null);
        assertThat(guard.canReadPublishedTable(4L, "public", "citizen", 9L)).isFalse();
        assertThat(guard.canReadPublishedTable(4L, "public", "citizen", null)).isFalse();
        asset.setTechLeaderUserId(9L);
        assertThat(guard.canReadPublishedTable(4L, "public", "citizen", 9L)).isTrue();
    }

    @Test
    void exactTableListGuardDoesNotHideInfrastructureOrOtherBusinessFailures() {
        var unavailable = new IllegalStateException("test database unavailable");
        when(applies.databaseNow()).thenThrow(unavailable);
        assertThatThrownBy(() -> guard.canReadPublishedTable(4L, "public", "citizen", 9L)).isSameAs(unavailable);
        var changed = new ServiceException(
                cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED);
        doThrow(changed).when(applies).databaseNow();
        assertThatThrownBy(() -> guard.canReadPublishedTable(4L, "public", "citizen", 9L)).isSameAs(changed);
    }

    @Test
    void disappearingGrantCannotReturnFullColumnMetadata() {
        assertThatThrownBy(() -> service.getColumnList(4L, "citizen")).isInstanceOf(ServiceException.class);
        verify(database, never()).getQueryTableColumns(any());
    }

    @Test
    void genuineIndependentOwnerKeepsNonMarketQueryPath() {
        asset.setTechLeaderUserId(9L);
        assertThatCode(() -> service.executeQuery(request())).doesNotThrowAnyException();
        verify(database).executeQuery(any());
        verify(applies, never()).selectEffective(any(), any(), any());
    }

    @Test
    void independentOwnershipLostDuringQueryAlsoRejectsResult() {
        asset.setTechLeaderUserId(9L);
        when(database.executeQuery(any())).thenAnswer(call -> {
            asset.setTechLeaderUserId(10L);
            return CommonResult.success(new DatabasePreviewRespDTO());
        });
        assertThatThrownBy(() -> service.executeQuery(request())).isInstanceOf(ServiceException.class);
        verify(database).executeQuery(any());
    }

    @Test
    void catalogBypassCheckCannotBorrowAnEffectiveApplication() {
        assertThatCode(() -> guard.assertPublishedReadable(asset, 9L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.assertPublishedReadableWithoutApply(asset, 9L))
                .isInstanceOf(ServiceException.class);
        asset.setBusinessLeaderUserId(9L);
        assertThatCode(() -> guard.assertPublishedReadableWithoutApply(asset, 9L)).doesNotThrowAnyException();
    }

    @Test
    void anotherSchemaOrQuotedCaseDoesNotBlockAnIndependentlyAuthorizedTable() {
        asset.setTechLeaderUserId(9L);
        var otherSchema = DataAssetDO.builder().id(6L).status(DataAssetDO.STATUS_PUBLISHED)
                .schemaName("other").tableName("citizen").build();
        var otherCase = DataAssetDO.builder().id(7L).status(DataAssetDO.STATUS_PUBLISHED)
                .schemaName("public").tableName("Citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, otherSchema, otherCase));
        assertThatCode(() -> service.executeQuery(request())).doesNotThrowAnyException();
        verify(database).executeQuery(argThat(req -> req.getSql().equals("SELECT * FROM \"public\".\"citizen\"")));
        verify(applies, never()).selectEffective(any(), any(), any());
    }

    @Test
    void permissionForPublicCannotAuthorizeTheOtherSchema() {
        asset.setTechLeaderUserId(9L);
        var other = DataAssetDO.builder().id(6L).status(DataAssetDO.STATUS_PUBLISHED)
                .schemaName("other").tableName("citizen").build();
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset, other));
        var plan = new RestrictedSelectRespDTO();
        plan.setSchemaName("other");
        plan.setTableName("citizen");
        plan.setNormalizedSql("SELECT * FROM \"other\".\"citizen\"");
        when(database.parseRestrictedSelect(any())).thenReturn(CommonResult.success(plan));
        assertThatThrownBy(() -> service.executeQuery(request())).isInstanceOf(ServiceException.class);
        verify(database, never()).executeQuery(any());
    }

    @Test
    void incompleteLegacyBindingCannotBeTreatedAsAnUnregisteredTable() {
        asset.setSchemaName(null);
        asset.setTechLeaderUserId(9L);
        assertThatThrownBy(() -> service.executeQuery(request())).isInstanceOf(ServiceException.class);
        verify(database, never()).executeQuery(any());
    }

    private static DataQueryExecuteReqVO request() {
        var request = new DataQueryExecuteReqVO();
        request.setDatasourceId(4L);
        request.setSql("select * from citizen");
        return request;
    }
}
