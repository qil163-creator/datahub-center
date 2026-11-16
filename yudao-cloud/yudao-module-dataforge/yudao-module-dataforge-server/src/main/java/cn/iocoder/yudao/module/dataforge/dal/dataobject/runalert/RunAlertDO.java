package cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 任务运行失败通知投递记录 DO：每个运行实例、事件、渠道、接收人一条。
 *
 * <p>状态只经 {@link cn.iocoder.yudao.module.dataforge.dal.mysql.runalert.RunAlertMapper} 的条件更新迁移，
 * 不通过实体整行更新，避免覆盖并发节点持有的租约。</p>
 */
@TableName(value = "dh_dataforge_run_alert", autoResultMap = true)
@KeySequence("dh_dataforge_run_alert_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunAlertDO extends BaseDO {

    /** 渠道：站内信（system 侧按幂等键去重，可安全重发） */
    public static final int CHANNEL_NOTIFY = 1;
    /** 渠道：短信（无去重契约，结果未确认时不自动重发） */
    public static final int CHANNEL_SMS = 2;
    /** 渠道：邮件（无去重契约，结果未确认时不自动重发） */
    public static final int CHANNEL_MAIL = 3;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENDING = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_DEAD = 3;
    public static final int STATUS_UNKNOWN = 4;
    public static final int STATUS_ACKNOWLEDGED = 5;

    public static final String EVENT_FAILURE = "FAILURE";

    /** 未找到启用接收人时的占位接收人编号 */
    public static final long NO_RECEIVER = 0L;

    /** 编号 */
    @TableId
    private Long id;

    /** 运行来源：DEVELOPMENT、DATA_ACCESS、ETL、INTEGRATION、JOB */
    private String sourceKind;

    /** 来源运行记录编号 */
    private Long runId;

    /** 来源任务编号 */
    private Long taskId;

    /** 任务名称快照 */
    private String taskName;

    /** 运行实例名称快照 */
    private String runName;

    /** 事件类型：FAILURE */
    private String eventType;

    /** 失败时间 */
    private LocalDateTime occurredTime;

    /** 接收人用户编号；0 表示未找到启用的接收人 */
    private Long receiverUserId;

    /** 渠道：1-站内信 2-短信 3-邮件 */
    private Integer channel;

    /** 登记时定稿的模板参数 JSON 对象 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String templateParams;

    /** 状态：0-待发送 1-发送中 2-已送达 3-死信 4-结果未知 5-已人工关闭 */
    private Integer status;

    /** 已认领发送次数 */
    private Integer attempts;

    /** 最早可再次认领时间 */
    private LocalDateTime availableAt;

    /** 发送租约令牌 */
    private String leaseToken;

    /** 发送租约到期时间 */
    private LocalDateTime leaseUntil;

    /** system 模块返回的站内信、短信日志或邮件日志编号 */
    private Long receiptId;

    /** 最近一次失败原因代码 */
    private String lastError;

    /** 送达或人工关闭时间 */
    private LocalDateTime handledAt;

    /** 人工处置动作：RETRY、ACKNOWLEDGE */
    private String dispositionAction;

    /** 人工处置原因 */
    private String dispositionReason;

    /** 人工处置的调度执行身份 */
    private String dispositionCommand;

}
