package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName(value = "dh_governance_quality_work_order_submission", autoResultMap = true)
@KeySequence("dh_governance_quality_work_order_submission_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderSubmissionDO extends BaseDO {

    public static final String TYPE_SOURCE_FIX = "SOURCE_FIX";

    public static final String TYPE_PLATFORM_REPAIR = "PLATFORM_REPAIR";

    public static final String TYPE_EXPLAIN = "EXPLAIN";

    public static final String TYPE_EXCEPTION = "EXCEPTION";

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    public static final String RECHECK_QUEUED = "QUEUED";

    public static final String RECHECK_RUNNING = "RUNNING";

    public static final String RECHECK_PASS = "PASS";

    public static final String RECHECK_FAIL = "FAIL";

    public static final String RECHECK_WAITING = "WAITING_DATA";

    public static final String RECHECK_ERROR = "ERROR";

    @TableId
    private Long id;

    private Long workOrderId;

    private Integer revision;

    private String submissionType;

    private String content;

    private String evidenceRef;

    private Long repairOrderId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String issueIds;

    private String payloadHash;

    private Long submittedBy;

    private LocalDateTime submittedAt;

    private String status;

    private String requestKey;

    private Long instanceId;

    private String recheckStatus;

    private Integer comparableFlag;

    private String lastError;

    private String exceptionScope;

    private LocalDateTime exceptionExpireAt;

}
