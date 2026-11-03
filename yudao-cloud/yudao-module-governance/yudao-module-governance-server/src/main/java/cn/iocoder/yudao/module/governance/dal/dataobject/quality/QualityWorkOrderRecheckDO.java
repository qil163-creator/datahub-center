package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/** 一次复检的持久回执；整改提交可以有多轮，已结束轮次不覆盖。 */
@TableName("dh_governance_quality_work_order_recheck")
@KeySequence("dh_governance_quality_work_order_recheck_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderRecheckDO extends BaseDO {

    public static final String STATUS_OBSOLETE = "OBSOLETE";

    @TableId
    private Long id;

    private Long workOrderId;

    private Long submissionId;

    private Integer attemptNo;

    private String requestId;

    private String requestKey;

    private Long instanceId;

    private Long requestedBy;

    private LocalDateTime requestedAt;

    private String status;

    private Integer comparableFlag;

    private String lastError;

    private LocalDateTime finishedAt;

    /** 版本化的复检判定依据，不存原始 SQL、行数据或连接凭据。 */
    private String evidenceJson;
}
