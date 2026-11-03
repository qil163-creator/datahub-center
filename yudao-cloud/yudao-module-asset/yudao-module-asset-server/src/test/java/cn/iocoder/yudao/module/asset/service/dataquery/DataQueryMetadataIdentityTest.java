package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessService;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataQueryMetadataIdentityTest {
    private DataQueryServiceImpl service;
    private DatabaseApi database;
    private AssetAccessGuard guard;
    private MarketAccessService market;
    private org.mockito.MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        service = new DataQueryServiceImpl();
        database = mock(DatabaseApi.class);
        guard = mock(AssetAccessGuard.class);
        market = mock(MarketAccessService.class);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "assetAccessGuard", guard);
        ReflectionTestUtils.setField(service, "marketAccessService", market);
        when(database.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of()));

        when(guard.canReadPublishedTable(any(), any(), any(), any())).thenReturn(true);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
    }

    @AfterEach
    void clear() {
        security.close();
    }

    private static DatabaseQueryTableRespDTO table(String schema, String name, String reference) {
        var table = new DatabaseQueryTableRespDTO();
        table.setSchemaName(schema);
        table.setTableName(name);
        table.setTableReference(reference);
        table.setNormalizedSql("SELECT * FROM " + reference);
        return table;
    }

    @Test
    void metadataReadsMustPreserveQuotedTableIdentity() {
        var table = table("Gov.Schema", "Citizen\". One", "\"Gov.Schema\".\"Citizen\"\". One\"");
        when(database.resolveQueryTable(4L, table.getTableReference())).thenReturn(CommonResult.success(table));
        service.getColumnList(4L, table.getTableReference());
        var metadata = ArgumentCaptor.forClass(DatabaseQueryColumnsReqDTO.class);
        verify(database).getQueryTableColumns(metadata.capture());
        assertThat(metadata.getValue().getSchemaName()).isEqualTo("Gov.Schema");
        assertThat(metadata.getValue().getTableName()).isEqualTo("Citizen\". One");
        verify(market).findEffectiveApplyForTable(9L, 4L, "Gov.Schema", "Citizen\". One");
        verify(database, never()).getTableColumnList(any(), any());
    }

    @Test
    void tableListReturnsQualifiedReferencesAndFiltersExactBindings() {
        var publicTable = table("public", "citizen", "\"public\".\"citizen\"");
        var other = table("other", "citizen", "\"other\".\"citizen\"");
        when(database.getQueryTableList(4L)).thenReturn(CommonResult.success(List.of(publicTable, other)));
        when(guard.canReadPublishedTable(4L, "other", "citizen", 9L)).thenReturn(false);
        assertThat(service.getTableList(4L)).containsExactly(publicTable.getTableReference());
        verify(guard, atLeastOnce()).assertPublishedTableReadable(4L, "public", "citizen", 9L, false);
        verify(database, never()).getTableNameList(any());
    }

    @Test
    void oldIncompleteMetadataResponseCannotFallBackToBareName() {
        var old = table(null, "citizen", null);
        when(database.resolveQueryTable(any(), any())).thenReturn(CommonResult.success(old));
        assertThatThrownBy(() -> service.getColumnList(4L, "citizen")).isInstanceOf(ServiceException.class);
        verify(database, never()).getQueryTableColumns(any());
        verify(database, never()).getTableColumnList(any(), any());
    }

    @Test
    void missingUserAndAssetGrantDenialAreRejectedBeforeMetadataRead() {
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
        assertThatThrownBy(() -> service.getTableList(4L)).isInstanceOf(ServiceException.class);
        verify(database, never()).getQueryTableList(any());
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
        when(database.resolveQueryTable(any(), any())).thenReturn(CommonResult.success(table("public", "citizen", "public.citizen")));
        doThrow(new ServiceException(1_500_026_028, "denied")).when(guard)
                .assertPublishedTableReadable(4L, "public", "citizen", 9L, false);
        assertThatThrownBy(() -> service.getColumnList(4L, "citizen")).isInstanceOf(ServiceException.class);
        verify(database, never()).getQueryTableColumns(any());
    }

    @Test
    void independentPermissionMustRemainValidUntilMetadataReturns() {
        when(database.resolveQueryTable(any(), any())).thenReturn(CommonResult.success(table("public", "citizen", "public.citizen")));
        doAnswer(call -> {
            doThrow(new ServiceException(1_500_026_028, "changed")).when(guard)
                    .assertPublishedTableReadable(4L, "public", "citizen", 9L, false);
            return CommonResult.success(List.of());
        }).when(database).getQueryTableColumns(any());
        assertThatThrownBy(() -> service.getColumnList(4L, "citizen")).isInstanceOf(ServiceException.class);
        verify(database).getQueryTableColumns(any());
    }
}
