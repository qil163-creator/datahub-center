package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketAccessBatchTest {
    private final MarketAccessServiceImpl service = new MarketAccessServiceImpl();
    private final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);

    @BeforeEach
    void injectDependencies() {
        ReflectionTestUtils.setField(service, "applyMapper", applies);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
    }

    @Test
    void emptySelectionNeverBecomesAllUsersAssets() {
        assertThat(service.findEffectiveApplies(9L, List.of())).isEmpty();
        assertThat(service.findEffectiveApplies(null, null)).isEmpty();
        verifyNoInteractions(applies, assets);
    }

    @Test
    void batchUsesOneDatabaseClockAndOneAssetReadForFrozenValidation() {
        var now = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(applies.databaseNow()).thenReturn(now);
        var newest = apply(8L); var older = apply(7L);
        when(applies.selectEffectiveForUser(9L, List.of(5L), now)).thenReturn(List.of(newest, older));
        when(assets.selectByIds(Set.of(5L))).thenReturn(List.of(asset()));
        assertThat(service.findEffectiveApplies(9L, List.of(5L))).containsEntry(5L, newest).hasSize(1);
        verify(applies).databaseNow(); verify(assets).selectByIds(Set.of(5L));
        verify(assets, never()).selectById(anyLong());
    }

    @Test
    void missingClockAndBrokenFrozenBindingNeverProduceAuthorizedFlags() {
        assertThatThrownBy(() -> service.findEffectiveApplies(9L, null)).isInstanceOf(ServiceException.class);
        when(applies.databaseNow()).thenReturn(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(applies.selectEffectiveForUser(eq(9L), isNull(), any())).thenReturn(List.of(apply(8L)));
        var changed = asset(); changed.setTableName("changed");
        when(assets.selectByIds(Set.of(5L))).thenReturn(List.of(changed));
        assertThatThrownBy(() -> service.findEffectiveApplies(9L, null)).isInstanceOf(ServiceException.class);
    }

    private static MarketResourceApplyDO apply(long id) {
        return MarketResourceApplyDO.builder().id(id).assetId(5L).snapshot("""
                {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                 "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                """).build();
    }

    private static DataAssetDO asset() {
        return DataAssetDO.builder().id(5L).assetType(1).status(1).datasourceId(4L).schemaName("public")
                .tableName("citizen").publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1").build();
    }
}
