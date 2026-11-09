package cn.iocoder.yudao.module.asset.dal.dataobject.monitor;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据资产-运营指标日快照 DO：每个资产每天一行，记录近 30 天使用情况与热度、价值评估结果。
 *
 * <p>由 {@code assetOperationMetricJob} 每日计算（也可手工刷新当天），同一天重算整体替换；
 * 历史快照用于热度/价值趋势与盘点报告。</p>
 */
@TableName("dh_asset_operation_metric")
@KeySequence("dh_asset_operation_metric_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetOperationMetricDO extends BaseDO {

    @TableId
    private Long id;

    /** 统计日期 */
    private LocalDate statDate;

    /** 统计窗口天数 */
    private Integer windowDays;

    /** 资产编号 */
    private Long assetId;

    /** 资产名称快照 */
    private String assetName;

    /** 资产编码快照 */
    private String assetCode;

    /** 资产类型：1-库表 2-接口 3-文件 */
    private Integer assetType;

    /** 资产状态：0-未发布 1-已发布 2-已下线 */
    private Integer assetStatus;

    /** 资产类目 */
    private Long categoryId;

    /** 数仓分层 */
    private String layer;

    /** 来源系统 */
    private String sourceSystemName;

    /** 归属部门 */
    private Long ownerDeptId;

    /** 窗口内浏览次数 */
    private Long browseCount;

    /** 窗口内浏览人数 */
    private Long browseUserCount;

    /** 窗口内申请次数 */
    private Long applyCount;

    /** 窗口内使用（预览/查询）次数 */
    private Long usageCount;

    /** 窗口内使用人数 */
    private Long usageUserCount;

    /** 累计收藏数 */
    private Long favoriteCount;

    /** 当前有效授权数 */
    private Long activeGrantCount;

    /** 当前有效授权覆盖部门数 */
    private Long grantDeptCount;

    /** 窗口内开放网关调用次数 */
    private Long apiCallCount;

    /** 窗口内开放网关成功次数 */
    private Long apiSuccessCount;

    /** 窗口内调用应用数 */
    private Long apiAppCount;

    /** 窗口内质量问题反馈数 */
    private Long feedbackCount;

    /** 最近一次真实使用时间（申请、预览/查询、网关调用） */
    private LocalDateTime lastUsedTime;

    /** 闲置天数：距最近使用，从未使用按发布或创建时间计 */
    private Integer idleDays;

    /** 热度分 0-100 */
    private BigDecimal heatScore;

    /** 使用广度分 0-100 */
    private BigDecimal breadthScore;

    /** 质量分 0-100，未评估为空 */
    private BigDecimal qualityScore;

    /** 元数据完整度分 0-100 */
    private BigDecimal completenessScore;

    /** 综合价值分 0-100 */
    private BigDecimal valueScore;

    /** 价值等级：HIGH/MEDIUM/LOW/IDLE */
    private String valueLevel;

    /** 运营建议：KEEP/IMPROVE_QUALITY/COMPLETE_METADATA/PROMOTE/RETIRE_REVIEW */
    private String suggestion;

    /** 是否取得了开放网关调用统计（数据服务不可用时为 false，接口调用按 0 计） */
    private Boolean apiStatsAvailable;

}
