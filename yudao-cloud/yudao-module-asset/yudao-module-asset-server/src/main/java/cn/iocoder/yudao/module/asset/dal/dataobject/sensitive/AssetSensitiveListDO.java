package cn.iocoder.yudao.module.asset.dal.dataobject.sensitive;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 资产字段分类清单；独立记录不代表已经向数据出口应用脱敏策略。 */
@TableName("dh_asset_sensitive_list")
@KeySequence("dh_asset_sensitive_list_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetSensitiveListDO extends BaseDO {

    @TableId
    private Long id;

    private String sourceRecordId;

    private Long assetId;

    private Long columnId;

    private String columnName;

    private Long categoryId;

    /** 本地明确绑定的规则编号；来源缺失时保持空，不从名称猜测。 */
    private Long ruleId;

    private String classificationName;

    private String levelName;

    private String levelColor;

    private String ruleName;

    private String description;

    private Integer status;

    private Integer revision;

    /** 仅为来源昵称，不参与本地身份或授权。 */
    private String sourceCreatorName;

    private String sourceUpdaterName;
}
