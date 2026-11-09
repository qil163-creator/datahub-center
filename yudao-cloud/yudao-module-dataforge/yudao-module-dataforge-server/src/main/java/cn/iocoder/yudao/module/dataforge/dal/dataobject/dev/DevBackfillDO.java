package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 数据开发-补数据批次 DO：对一个开发任务按业务日期区间逐日重跑。
 *
 * <p>批次创建时冻结任务当前保存版本；推进时每个业务日期登记一次普通运行（触发方式 {@code BACKFILL}），
 * 沿用单任务单活跃运行、上游同业务日期依赖与质量检查等既有护栏，因此日期之间严格串行。</p>
 */
@TableName("dh_dataforge_dev_backfill")
@KeySequence("dh_dataforge_dev_backfill_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevBackfillDO extends BaseDO {

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED);

    /** 日期顺序：从早到晚 */
    public static final String ORDER_ASC = "ASC";

    /** 日期顺序：从晚到早 */
    public static final String ORDER_DESC = "DESC";

    /** 失败策略：某日失败即停止，剩余日期取消 */
    public static final String POLICY_STOP = "STOP";

    /** 失败策略：某日失败继续后续日期 */
    public static final String POLICY_CONTINUE = "CONTINUE";

    @TableId
    private Long id;

    /** 开发任务编号 */
    private Long taskId;

    /** 任务名称（冗余） */
    private String taskName;

    /** 创建时冻结的任务保存版本 */
    private Long versionId;

    /** 冻结版本的执行身份 */
    private Long executionUserId;

    /** 起始业务日期（含） */
    private LocalDate startDate;

    /** 截止业务日期（含） */
    private LocalDate endDate;

    /** 日期顺序：ASC/DESC */
    private String dateOrder;

    /** 失败策略：STOP/CONTINUE */
    private String failurePolicy;

    /** 状态：RUNNING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 日期总数 */
    private Integer totalCount;

    /** 成功日期数 */
    private Integer successCount;

    /** 失败日期数 */
    private Integer failedCount;

    /** 取消日期数 */
    private Integer cancelledCount;

    /** 是否已请求取消；已提交日期等待引擎确认终态后批次才转为 CANCELLED */
    private Boolean cancelRequested;

    /** 发起请求标识（幂等） */
    private String requestId;

    /** 备注 */
    private String remark;

    /** 最近一次失败或终止原因 */
    private String lastError;

    /** 结束时间 */
    private LocalDateTime finishTime;

}
