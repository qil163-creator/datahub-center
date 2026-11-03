package cn.iocoder.yudao.module.asset.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("dh_asset_quality_task")
@KeySequence("dh_asset_quality_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetQualityTaskDO extends BaseDO {

    public static final int RUN_STATUS_NEVER = 0;

    public static final int RUN_STATUS_RUNNING = 1;

    public static final int RUN_STATUS_SUCCESS = 2;

    public static final int RUN_STATUS_FAILED = 3;

    public static final int RUN_STATUS_UNKNOWN = 4;

    @TableId
    private Long id;

    private Long assetId;

    /** 同步后的治理质量任务编号 */
    private Long qualityTaskId;

    private String taskName;

    private Integer scheduleType;

    private String cronExpression;

    private Integer enabled;

    private Integer runStatus;

    private LocalDateTime lastRunTime;

    private LocalDateTime lastFinishTime;

    private Long totalCount;

    private Long issueCount;

    private BigDecimal qualityScore;

    private Integer deletePending;

    private String remoteError;

    private String remark;

    /** 任务内完整评测配置；旧规则任务保持为空。 */
    private String evaluationJson;

    /** 未得到执行回执时保留，重试复用治理质量运行幂等键。 */
    private String pendingRunKey;

}
