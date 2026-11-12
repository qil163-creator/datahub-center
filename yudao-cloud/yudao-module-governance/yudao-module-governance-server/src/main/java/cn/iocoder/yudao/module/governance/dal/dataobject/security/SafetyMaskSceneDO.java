package cn.iocoder.yudao.module.governance.dal.dataobject.security;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据安全-动态脱敏场景开关：每个场景一行。场景缺失时按启用处理（失败关闭）。
 */
@TableName("dh_governance_mask_scene")
@KeySequence("dh_governance_mask_scene_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SafetyMaskSceneDO extends BaseDO {

    @TableId
    private Long id;
    /** 场景编码，取值见 SafetyMaskSceneEnum */
    private String sceneCode;
    /** 是否启用动态脱敏 */
    private Boolean enabled;
    /** 备注 */
    private String remark;
    /** 乐观锁修订号 */
    private Integer revision;
}
