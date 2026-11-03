package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketFavoriteDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFavoriteMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_AUTHORIZED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketConsumerDetailAuthorizationTest {
    private final MarketResourceServiceImpl service = new MarketResourceServiceImpl();
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final DataAssetService catalog = mock(DataAssetService.class);
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    private final MarketResourceCommentMapper comments = mock(MarketResourceCommentMapper.class);
    private final MarketFavoriteMapper favorites = mock(MarketFavoriteMapper.class);
    private final MarketAccessService access = mock(MarketAccessService.class);
    private final MarketDeliveryService delivery = mock(MarketDeliveryService.class);
    private final MarketDisplayHelper display = mock(MarketDisplayHelper.class);
    private final DataAssetDetailRespVO detail = new DataAssetDetailRespVO();

    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MarketResourceApplyDO.class);
    }

    @BeforeEach
    void setUp() {
        Map.ofEntries(Map.entry("assetMapper", assets), Map.entry("dataAssetService", catalog),
                Map.entry("objectGuard", guard), Map.entry("applyMapper", applies), Map.entry("commentMapper", comments),
                Map.entry("favoriteMapper", favorites), Map.entry("marketAccessService", access),
                Map.entry("marketDeliveryService", delivery), Map.entry("displayHelper", display))
                .forEach((name, value) -> ReflectionTestUtils.setField(service, name, value));
        when(guard.requireUserId()).thenReturn(9L);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L).status(1).build());
        var asset = new DataAssetRespVO(); asset.setId(5L); asset.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        asset.setAssetName("公开目录名称"); asset.setDatasourceHost("fixture.example.invalid");
        detail.setAsset(asset);
        detail.setColumns(List.of(column("Id"), column("id")));
        detail.setQualityTasks(List.of(new AssetQualityTaskRespVO()));
        when(catalog.getPublishedAssetDetail(5L)).thenReturn(detail);
        var pending = new MarketResourceApplyDO(); pending.setAssetId(5L); pending.setId(77L);
        pending.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applies.selectLatestByUserAndAssetIds(9L, List.of(5L))).thenReturn(List.of(pending));
        var favorite = new MarketFavoriteDO(); favorite.setAssetId(5L);
        when(favorites.selectByUserAndAssets(9L, List.of(5L))).thenReturn(List.of(favorite));
    }

    @Test
    void unauthenticatedServiceCallCannotReadEvenThePublishedOverview() {
        when(guard.requireUserId()).thenThrow(new ServiceException(MARKET_APPLY_FORBIDDEN));
        assertThatThrownBy(() -> service.getConsumerDetail(5L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(assets, catalog, applies, comments, favorites, access, delivery);
    }

    @Test
    void pendingApplicationShowsActorStateWithoutExposingFieldsOrQualityRules() {
        var result = service.getConsumerDetail(5L);
        assertThat(result.getAsset().getAssetName()).isEqualTo("公开目录名称");
        assertThat(result.getAsset().getDatasourceHost()).isNull();
        assertThat(result.getAsset().getAuthorized()).isFalse();
        assertThat(result.getAsset().getUsable()).isFalse();
        assertThat(result.getAsset().getMyApplyId()).isEqualTo(77L);
        assertThat(result.getAsset().getMyApplyStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(result.getAsset().getFavorite()).isTrue();
        assertThat(result.getColumns()).isEmpty();
        assertThat(result.getQualityTasks()).isEmpty();
        verify(access, never()).requireQuerySnapshot(any(), any());
    }

    @Test
    void approvedFieldsUsePhysicalCaseAndRevalidateBeforeReturning() {
        var authorization = authorize("[\"Id\"]");
        var result = service.getConsumerDetail(5L);
        assertThat(result.getAsset().getAuthorized()).isTrue();
        assertThat(result.getAsset().getUsable()).isTrue();
        assertThat(result.getColumns()).extracting(DataAssetColumnRespVO::getColumnName).containsExactly("Id");
        assertThat(result.getQualityTasks()).isEmpty();
        verify(access).assertStillCurrent(authorization);
        verify(access).findEffectiveApplies(9L, List.of(5L));
    }

    @Test
    void completeApprovedFieldSetCanReadQualityOverview() {
        authorize("[\"Id\",\"id\"]");
        var result = service.getConsumerDetail(5L);
        assertThat(result.getColumns()).hasSize(2);
        assertThat(result.getQualityTasks()).hasSize(1);
    }

    @Test
    void unconfirmedDeliveryCannotExposeFieldsEvenWhenApprovalIsEffective() {
        authorize("[\"Id\",\"id\"]");
        when(delivery.isConfirmedApply(any())).thenReturn(false);
        var result = service.getConsumerDetail(5L);
        assertThat(result.getAsset().getAuthorized()).isTrue();
        assertThat(result.getAsset().getUsable()).isFalse();
        assertThat(result.getColumns()).isEmpty();
        assertThat(result.getQualityTasks()).isEmpty();
        verify(access, never()).requireQuerySnapshot(any(), any());
    }

    @Test
    void approvalRevokedAfterCardEnrichmentCannotReleaseFields() {
        authorize("[\"Id\"]");
        when(access.findEffectiveApply(9L, 5L)).thenReturn(null);
        assertThatThrownBy(() -> service.getConsumerDetail(5L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_APPLY_NOT_AUTHORIZED.getCode()));
        verify(access, never()).requireQuerySnapshot(any(), any());
    }

    @Test
    void authorizationChangedDuringMetadataReadRejectsTheResponse() {
        var authorization = authorize("[\"Id\"]");
        doThrow(new ServiceException(MARKET_QUERY_AUTHORIZATION_CHANGED)).when(access).assertStillCurrent(authorization);
        assertThatThrownBy(() -> service.getConsumerDetail(5L)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(MARKET_QUERY_AUTHORIZATION_CHANGED.getCode()));
    }

    private MarketQuerySnapshot authorize(String columns) {
        var apply = new MarketResourceApplyDO(); apply.setId(7L); apply.setAssetId(5L);
        apply.setSnapshot("{\"frozenColumns\":" + columns + "}");
        when(access.findEffectiveApplies(9L, List.of(5L))).thenReturn(Map.of(5L, apply));
        when(delivery.isConfirmedApply(apply)).thenReturn(true);
        when(access.findEffectiveApply(9L, 5L)).thenReturn(apply);
        var authorization = new MarketQuerySnapshot(9L, 7L, 5L, List.of(), MarketFieldScope.effectiveScope(apply),
                4L, "public", "fixture", LocalDateTime.of(2026, 10, 22, 0, 0));
        when(access.requireQuerySnapshot(9L, 7L)).thenReturn(authorization);
        return authorization;
    }

    private static DataAssetColumnRespVO column(String name) {
        var column = new DataAssetColumnRespVO(); column.setColumnName(name); return column;
    }
}
