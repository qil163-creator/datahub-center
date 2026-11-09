package cn.iocoder.yudao.module.asset.service.monitor;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 资产运营评分规则（纯函数，便于审计与单测）。
 *
 * <ul>
 *   <li>热度：活跃度 = 浏览 + 3×使用 + 5×申请 + 3×收藏 + 2×(浏览人数 + 使用人数) + 网关调用/10，
 *       热度 = min(100, 25×log10(1 + 活跃度))；活跃度 9→25、99→50、999→75、9999→100。</li>
 *   <li>使用广度：min(100, 20×授权部门数 + 5×有效授权数 + 10×调用应用数)。</li>
 *   <li>质量：资产健康分（0-100），未评估时不参与加权。</li>
 *   <li>完整度：描述、类目、技术负责人、业务负责人、归属部门、更新周期、共享属性、主题域 8 项的满足比例。</li>
 *   <li>价值：热度 35% + 广度 25% + 质量 20% + 完整度 20%；质量未评估时其权重按比例分给其余三项。</li>
 *   <li>等级：已发布且闲置 ≥ 90 天并且没有有效授权为 IDLE；否则 ≥70 HIGH、≥40 MEDIUM、其余 LOW。</li>
 * </ul>
 */
public final class AssetOperationScoring {

    /** 判定闲置的天数 */
    public static final int IDLE_DAYS = 90;

    public static final String LEVEL_HIGH = "HIGH";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_LOW = "LOW";
    public static final String LEVEL_IDLE = "IDLE";

    /** 保持现状 */
    public static final String SUGGEST_KEEP = "KEEP";
    /** 高热度但质量偏低：优先治理 */
    public static final String SUGGEST_IMPROVE_QUALITY = "IMPROVE_QUALITY";
    /** 元数据不完整：补全编目信息 */
    public static final String SUGGEST_COMPLETE_METADATA = "COMPLETE_METADATA";
    /** 质量与完整度良好但少有人用：加强推广 */
    public static final String SUGGEST_PROMOTE = "PROMOTE";
    /** 长期闲置：评估下线或停止供应 */
    public static final String SUGGEST_RETIRE_REVIEW = "RETIRE_REVIEW";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AssetOperationScoring() {
    }

    /** 输入：一个资产在统计窗口内的使用信号 */
    public record Signals(long browse, long browseUsers, long usage, long usageUsers, long apply, long favorite,
                          long activeGrants, long grantDepts, long apiCalls, long apiApps) {
    }

    /** 输出：评分与建议 */
    public record Result(BigDecimal heat, BigDecimal breadth, BigDecimal quality, BigDecimal completeness,
                         BigDecimal value, String level, String suggestion) {
    }

    public static Result evaluate(Signals signals, DataAssetDO asset, int idleDays) {
        double heat = heat(signals);
        double breadth = breadth(signals);
        Double quality = quality(asset.getHealthScore());
        double completeness = completeness(asset);
        double value = quality == null
                ? (0.35 * heat + 0.25 * breadth + 0.20 * completeness) / 0.80
                : 0.35 * heat + 0.25 * breadth + 0.20 * quality + 0.20 * completeness;
        boolean published = Integer.valueOf(DataAssetDO.STATUS_PUBLISHED).equals(asset.getStatus());
        boolean idle = published && idleDays >= IDLE_DAYS && signals.activeGrants() == 0;
        String level = idle ? LEVEL_IDLE : value >= 70 ? LEVEL_HIGH : value >= 40 ? LEVEL_MEDIUM : LEVEL_LOW;
        return new Result(round(heat), round(breadth), quality == null ? null : round(quality), round(completeness),
                round(value), level, suggestion(idle, published, heat, quality, completeness));
    }

    static double heat(Signals s) {
        double activity = s.browse() + 3.0 * s.usage() + 5.0 * s.apply() + 3.0 * s.favorite()
                + 2.0 * (s.browseUsers() + s.usageUsers()) + s.apiCalls() / 10.0;
        return Math.min(100, 25 * Math.log10(1 + Math.max(0, activity)));
    }

    static double breadth(Signals s) {
        return Math.min(100, 20.0 * s.grantDepts() + 5.0 * s.activeGrants() + 10.0 * s.apiApps());
    }

    static Double quality(BigDecimal healthScore) {
        if (healthScore == null) {
            return null;
        }
        return Math.max(0, Math.min(100, healthScore.doubleValue()));
    }

    static double completeness(DataAssetDO asset) {
        int satisfied = 0;
        satisfied += StrUtil.isNotBlank(asset.getDescription()) ? 1 : 0;
        satisfied += asset.getCategoryId() != null ? 1 : 0;
        satisfied += asset.getTechLeaderUserId() != null ? 1 : 0;
        satisfied += asset.getBusinessLeaderUserId() != null ? 1 : 0;
        satisfied += asset.getOwnerDeptId() != null ? 1 : 0;
        satisfied += StrUtil.isNotBlank(asset.getUpdateCycle()) ? 1 : 0;
        Integer share = asset.getShareAttr() != null ? asset.getShareAttr() : asset.getShareAttribute();
        satisfied += share != null && share != DataAssetDO.SHARE_PENDING ? 1 : 0;
        satisfied += asset.getSubjectDomainId() != null || StrUtil.isNotBlank(asset.getSubjectDomainName()) ? 1 : 0;
        return satisfied * 100.0 / 8;
    }

    static String suggestion(boolean idle, boolean published, double heat, Double quality, double completeness) {
        if (idle) {
            return SUGGEST_RETIRE_REVIEW;
        }
        if (heat >= 50 && quality != null && quality < 60) {
            return SUGGEST_IMPROVE_QUALITY;
        }
        if (completeness < 60) {
            return SUGGEST_COMPLETE_METADATA;
        }
        if (published && heat < 25 && (quality == null || quality >= 80)) {
            return SUGGEST_PROMOTE;
        }
        return SUGGEST_KEEP;
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).min(HUNDRED).max(BigDecimal.ZERO);
    }

}
