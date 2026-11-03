package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_AUTHORIZED;

class MarketAccessServiceImplTest {

    private final MarketAccessServiceImpl service = new MarketAccessServiceImpl();

    @org.junit.jupiter.api.BeforeEach
    void configureIdentityBoundary() {
        ReflectionTestUtils.setField(service, "metadataIdentityGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedUserWithoutEffectiveGrantCannotReachDataRpc() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
        var applies = mock(MarketResourceApplyMapper.class);
        var database = mock(DatabaseApi.class);
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        when(applies.databaseNow()).thenReturn(now);
        ReflectionTestUtils.setField(service, "applyMapper", applies);
        ReflectionTestUtils.setField(service, "databaseApi", database);

        ServiceException error = assertThrows(ServiceException.class, () -> service.previewAuthorizedAsset(5L, 20));

        assertEquals(MARKET_APPLY_NOT_AUTHORIZED.getCode(), error.getCode());
        verify(applies).selectEffective(9L, 5L, now);
        verifyNoInteractions(database);
    }

    @Test
    void grantOwnedByAnotherUserCannotReachDataRpc() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
        var applies = mock(MarketResourceApplyMapper.class);
        var assets = mock(DataAssetMapper.class);
        var database = mock(DatabaseApi.class);
        var transactions = mock(PlatformTransactionManager.class);
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        when(applies.databaseNow()).thenReturn(now);
        var apply = MarketResourceApplyDO.builder().id(7L).assetId(5L).applyUserId(10L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(MarketResourceApplyDO.AUTH_YES)
                .snapshot("""
                        {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                         "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                        """).build();
        when(applies.selectEffective(9L, 5L, now)).thenReturn(apply);
        when(applies.selectForAccess(7L, 9L)).thenReturn(apply);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE).status(DataAssetDO.STATUS_PUBLISHED)
                .datasourceId(4L).schemaName("public").tableName("citizen")
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1").build());
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "applyMapper", applies);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);

        ServiceException error = assertThrows(ServiceException.class, () -> service.previewAuthorizedAsset(5L, 20));

        assertEquals(MARKET_APPLY_NOT_AUTHORIZED.getCode(), error.getCode());
        verify(applies).selectForAccess(7L, 9L);
        verifyNoInteractions(database);
    }

    @Test
    void unmatchedFieldScopeDoesNotReturnOriginalRows() {
        DatabasePreviewRespDTO preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("secret", "secret", "varchar")));
        preview.setRows(List.of(Map.of("secret", "plain")));
        assertThrows(ServiceException.class, () -> service.filterPreview(preview, MarketFieldScope.parse("district_code")));
        assertEquals("plain", preview.getRows().get(0).get("secret"));
    }

    @Test
    void blankScopeMeansAllApprovedFields() {
        DatabasePreviewRespDTO preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("district_code", "区县", "varchar")));
        preview.setRows(List.of(Map.of("district_code", "A")));
        DatabasePreviewRespDTO result = service.filterPreview(preview, MarketFieldScope.parse("  "));
        assertEquals(1, result.getColumns().size());
    }

    @Test
    void listedScopeKeepsMatchingColumns() {
        DataQueryResultRespVO result = new DataQueryResultRespVO();
        DataQueryResultRespVO.Column column = new DataQueryResultRespVO.Column();
        column.setName("district_code");
        result.setColumns(List.of(column));
        result.setRows(List.of(Map.of("district_code", "A", "secret", "x")));
        service.filterPreview(result, MarketFieldScope.parse("district_code"));
        assertEquals(1, result.getColumns().size());
        assertEquals("A", result.getRows().get(0).get("district_code"));
        assertEquals(null, result.getRows().get(0).get("secret"));
    }

    @Test
    void emptyParsedScopeIsRejected() {
        assertThrows(ServiceException.class, () -> service.assertQueryColumnsAllowed(MarketFieldScope.parse(",,,"), false, List.of("a")));
    }

    @Test
    void pausedOrExpiredApplyIsNotEffective() {
        var mapper = org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper.class);
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        org.mockito.Mockito.when(mapper.databaseNow()).thenReturn(now);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applyMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "displayHelper", new MarketDisplayHelper());
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setPaused(1);
        org.junit.jupiter.api.Assertions.assertFalse(service.isEffective(apply));
        apply.setPaused(0);
        apply.setApplyEndTime(now.minusSeconds(1));
        org.junit.jupiter.api.Assertions.assertFalse(service.isEffective(apply));
        apply.setApplyEndTime(now.plusSeconds(5));
        org.junit.jupiter.api.Assertions.assertTrue(service.isEffective(apply));
    }

    @ParameterizedTest
    @CsvSource({"1,10,false", "0,10,true", "-1,1,true", "-1,0,false", "-10,-1,false"})
    void permissionWindowIncludesStartAndExcludesEnd(long startOffset, long endOffset, boolean expected) {
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0, 0, 123_456_000);
        var mapper = org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper.class);
        org.mockito.Mockito.when(mapper.databaseNow()).thenReturn(now);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applyMapper", mapper);
        var apply = MarketResourceApplyDO.builder().status(MarketResourceApplyDO.STATUS_APPROVED)
                .ifAuth(MarketResourceApplyDO.AUTH_YES).paused(0)
                .applyStartTime(now.plusNanos(startOffset * 1000))
                .applyEndTime(now.plusNanos(endOffset * 1000)).build();
        assertEquals(expected, service.isEffective(apply));
    }

    @Test
    void unavailableClockDoesNotFallbackToNodeTime() {
        var mapper = org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applyMapper", mapper);
        var apply = MarketResourceApplyDO.builder().status(MarketResourceApplyDO.STATUS_APPROVED)
                .ifAuth(MarketResourceApplyDO.AUTH_YES).paused(0).build();
        assertThrows(ServiceException.class, () -> service.isEffective(apply));
        org.mockito.Mockito.when(mapper.databaseNow()).thenThrow(new IllegalStateException("test clock unavailable"));
        assertThrows(IllegalStateException.class, () -> service.isEffective(apply));
    }
}
