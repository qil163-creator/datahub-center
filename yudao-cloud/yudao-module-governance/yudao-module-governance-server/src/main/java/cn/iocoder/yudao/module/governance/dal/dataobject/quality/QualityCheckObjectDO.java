package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-检查对象 DO
 *
 * 数据源连接 + 表（支持第三方库）
 *
 * @author Li
 */
@TableName("dh_governance_quality_check_object")
@KeySequence("dh_governance_quality_check_object_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckObjectDO extends BaseDO {

    @TableId
    private Long id;

    /** 检查对象名称 */
    private String objectName;

    /** 数据源连接ID */
    private Long datasourceConnId;

    /** 数据源安全展示名称，仅用于详情富化与执行快照，不增加平台表字段。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String datasourceConnName;

    /** 数据连接类型（中台贴源库/明细库/主库/第三方库） */
    private String datasourceType;

    /** 数据连接实例 */
    private String datasourceInstance;

    /** 格式名称 */
    private String formatName;

    /** 表名称 */
    private String tableName;

    /** 关联元数据表ID（可空，第三方库无） */
    private Long tableId;

    /** 备注 */
    private String remark;

}
