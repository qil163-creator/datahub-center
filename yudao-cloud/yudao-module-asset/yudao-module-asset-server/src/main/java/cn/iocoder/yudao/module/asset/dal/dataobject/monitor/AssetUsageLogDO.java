package cn.iocoder.yudao.module.asset.dal.dataobject.monitor;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据资产-使用行为日志 DO
 *
 * 记录资产的浏览 / 申请 / 使用事件。
 */
@TableName("dh_asset_usage_log")
@KeySequence("dh_asset_usage_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetUsageLogDO extends BaseDO {

    /** 行为：浏览资产详情 */
    public static final int ACTION_BROWSE = 1;

    /** 行为：申请使用资产 */
    public static final int ACTION_APPLY = 2;

    /** 行为：使用资产（数据预览 / 查询） */
    public static final int ACTION_USAGE = 3;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 资产编号
     */
    private Long assetId;

    /**
     * 资产名称快照
     */
    private String assetName;

    /**
     * 资产提供方（来源系统）名称快照
     */
    private String providerName;

    /**
     * 行为类型：1-浏览 2-申请 3-使用
     */
    private Integer action;

    /**
     * 操作用户编号
     */
    private Long userId;

}
