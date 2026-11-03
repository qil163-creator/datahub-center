package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-审批批注 DO（问题行/单元格标记；写入前校验批注人是当前 BPM 任务办理人；整改页据此高亮）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_review_annotation")
@KeySequence("dh_dataforge_fill_review_annotation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillReviewAnnotationDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 提交快照编号
     */
    private Long submissionId;

    /**
     * BPM 流程实例编号
     */
    private String processInstanceId;

    /**
     * BPM 任务编号
     */
    private String bpmTaskId;

    /**
     * 问题行标识（空=整单意见）
     */
    private String rowId;

    /**
     * 问题字段码（空=整行意见）
     */
    private String fieldCode;

    /**
     * 批注内容
     */
    private String content;

    /**
     * 批注人编号
     */
    private Long annotatorUserId;

}
