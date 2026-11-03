package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据建模-来源系统，贴源逻辑表的归属类目
 */
@TableName("dh_modeling_source_system")
@KeySequence("dh_modeling_source_system_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceSystemDO extends BaseDO {

    @TableId
    private Long id;

    /** 来源系统名称 */
    private String name;

    /** 系统类型 */
    private String type;

    /** 排序 */
    private Integer sort;

    /** 描述 */
    private String description;

    /** 状态：0 启用，1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;

}
