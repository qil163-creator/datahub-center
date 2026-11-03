package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 问题行的当前可见内容摘要及处置状态；不留存源数据副本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_governance_quality_issue_row_state")
@KeySequence("dh_governance_quality_issue_row_state_seq")
public class QualityIssueRowStateDO extends BaseDO {

    @TableId
    private Long id;

    private Long issueId;

    private String rowKey;

    private String state;

    private Integer revision;

    private String remark;
}
