package cn.iocoder.yudao.module.asset.service.monitor;

import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.asset.service.monitor.AssetOperationScoring.*;
import static org.junit.jupiter.api.Assertions.*;

/** 资产运营评分规则：热度对数刻度、价值加权与质量缺失再分配、闲置判定与运营建议。 */
class AssetOperationScoringTest {

    private static final Signals NONE = new Signals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void heatIsLogScaledAndCapped() {
        assertEquals(0, heat(NONE), 1e-9);
        assertEquals(25, heat(new Signals(9, 0, 0, 0, 0, 0, 0, 0, 0, 0)), 1e-9);
        assertEquals(50, heat(new Signals(99, 0, 0, 0, 0, 0, 0, 0, 0, 0)), 1e-9);
        // 网关调用按 1/10 计入活跃度：9990 / 10 = 999 → 75
        assertEquals(75, heat(new Signals(0, 0, 0, 0, 0, 0, 0, 0, 9990, 0)), 1e-9);
        assertEquals(100, heat(new Signals(1_000_000, 0, 0, 0, 0, 0, 0, 0, 0, 0)), 1e-9);
    }

    @Test
    void breadthRewardsDepartmentsGrantsAndApps() {
        assertEquals(20 * 2 + 5 * 3 + 10, breadth(new Signals(0, 0, 0, 0, 0, 0, 3, 2, 0, 1)), 1e-9);
        assertEquals(100, breadth(new Signals(0, 0, 0, 0, 0, 0, 50, 50, 0, 50)), 1e-9);
    }

    @Test
    void completenessCountsEightCatalogFacts() {
        assertEquals(0, completeness(DataAssetDO.builder().shareAttr(DataAssetDO.SHARE_PENDING).build()), 1e-9);
        assertEquals(100, completeness(complete().build()), 1e-9);
        assertEquals(87.5, completeness(complete().description(" ").build()), 1e-9);
    }

    @Test
    void valueRedistributesTheQualityWeightWhenNotAssessed() {
        DataAssetDO assessed = complete().healthScore(new BigDecimal("50")).build();
        Result withQuality = evaluate(NONE, assessed, 0);
        // 0.35×0 + 0.25×0 + 0.20×50 + 0.20×100 = 30
        assertEquals(new BigDecimal("30.0"), withQuality.value());
        Result withoutQuality = evaluate(NONE, complete().build(), 0);
        // (0.20×100) / 0.80 = 25
        assertEquals(new BigDecimal("25.0"), withoutQuality.value());
        assertNull(withoutQuality.quality());
        assertEquals(new BigDecimal("100.0"), evaluate(NONE, complete().healthScore(new BigDecimal("180")).build(), 0).quality());
    }

    @Test
    void idleOnlyAppliesToPublishedAssetsWithoutActiveGrants() {
        DataAssetDO published = complete().status(DataAssetDO.STATUS_PUBLISHED).build();
        assertEquals(LEVEL_IDLE, evaluate(NONE, published, IDLE_DAYS).level());
        assertEquals(SUGGEST_RETIRE_REVIEW, evaluate(NONE, published, IDLE_DAYS).suggestion());
        assertNotEquals(LEVEL_IDLE, evaluate(NONE, published, IDLE_DAYS - 1).level());
        Signals granted = new Signals(0, 0, 0, 0, 0, 0, 1, 1, 0, 0);
        assertNotEquals(LEVEL_IDLE, evaluate(granted, published, 400).level());
        DataAssetDO draft = complete().status(DataAssetDO.STATUS_UNPUBLISHED).build();
        assertNotEquals(LEVEL_IDLE, evaluate(NONE, draft, 400).level());
    }

    @Test
    void suggestionsPrioritiseRetireThenQualityThenMetadataThenPromotion() {
        assertEquals(SUGGEST_RETIRE_REVIEW, suggestion(true, true, 90, 10.0, 10));
        assertEquals(SUGGEST_IMPROVE_QUALITY, suggestion(false, true, 60, 40.0, 100));
        assertEquals(SUGGEST_COMPLETE_METADATA, suggestion(false, true, 60, 90.0, 50));
        assertEquals(SUGGEST_PROMOTE, suggestion(false, true, 10, 90.0, 100));
        assertEquals(SUGGEST_KEEP, suggestion(false, false, 10, 90.0, 100));
        assertEquals(SUGGEST_KEEP, suggestion(false, true, 60, null, 100));
    }

    @Test
    void levelsFollowValueThresholds() {
        Signals busy = new Signals(5000, 300, 2000, 200, 300, 100, 20, 8, 100000, 6);
        Result high = evaluate(busy, complete().status(DataAssetDO.STATUS_PUBLISHED).healthScore(new BigDecimal("95")).build(), 0);
        assertEquals(LEVEL_HIGH, high.level());
        assertEquals(LEVEL_LOW, evaluate(NONE, DataAssetDO.builder().status(DataAssetDO.STATUS_PUBLISHED).build(), 3).level());
    }

    @Test
    void idleDaysAnchorOnLastUseThenPublishThenCreate() {
        LocalDate stat = LocalDate.of(2026, 11, 9);
        DataAssetDO asset = DataAssetDO.builder().publishTime(LocalDateTime.of(2026, 8, 1, 9, 0)).build();
        asset.setCreateTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertEquals(8, AssetOperationServiceImpl.idleDays(LocalDateTime.of(2026, 11, 1, 23, 0), asset, stat));
        assertEquals(100, AssetOperationServiceImpl.idleDays(null, asset, stat));
        asset.setPublishTime(null);
        assertEquals(312, AssetOperationServiceImpl.idleDays(null, asset, stat));
        assertEquals(0, AssetOperationServiceImpl.idleDays(LocalDateTime.of(2026, 11, 10, 1, 0), asset, stat));
    }

    private static DataAssetDO.DataAssetDOBuilder complete() {
        return DataAssetDO.builder().description("订单明细").categoryId(1L).techLeaderUserId(2L).businessLeaderUserId(3L)
                .ownerDeptId(4L).updateCycle("DAILY").shareAttr(DataAssetDO.SHARE_UNCONDITIONAL).subjectDomainId(5L);
    }

}
