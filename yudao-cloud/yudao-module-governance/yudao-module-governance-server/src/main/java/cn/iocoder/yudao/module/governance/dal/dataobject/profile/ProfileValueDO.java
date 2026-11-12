package cn.iocoder.yudao.module.governance.dal.dataobject.profile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据探查-Top 值：每个字段按出现次数倒序的前 N 个取值；敏感字段只保存计数。
 *
 * @author Li
 */
@TableName("dh_governance_profile_value")
@KeySequence("dh_governance_profile_value_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProfileValueDO extends BaseDO {

    @TableId
    private Long id;

    /** 探查记录编号 */
    private Long runId;

    /** 字段指标编号 */
    private Long profileColumnId;

    /** 排名，从 1 开始 */
    private Integer rankNo;

    /** 取值文本；已脱敏时为空 */
    private String valueText;

    /** 出现次数 */
    private Long valueCount;

    /** 已脱敏：0 否 1 是 */
    private Integer valueMasked;

}
