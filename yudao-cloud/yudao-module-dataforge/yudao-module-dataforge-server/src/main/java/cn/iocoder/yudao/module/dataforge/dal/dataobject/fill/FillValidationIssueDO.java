package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-校验问题 DO（submissionId 为空表示草稿校验，非空表示提交强校验结果）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_validation_issue")
@KeySequence("dh_dataforge_fill_validation_issue_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillValidationIssueDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 提交快照编号（空=草稿校验）
     */
    private Long submissionId;

    /**
     * 规则编号
     */
    private String ruleCode;

    /**
     * 级别：1-错误（阻断提交） 2-警告，见 FillValidationSeverityEnum
     */
    private Integer severity;

    /**
     * 问题行标识
     */
    private String rowId;

    /**
     * 问题字段码
     */
    private String fieldCode;

    /**
     * 显示行号（校验时的可见行号，便于用户定位）
     */
    private Integer displayRowNo;

    /**
     * 问题描述
     */
    private String message;

}
