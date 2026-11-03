package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-可靠事件发件箱 DO（与业务同事务写入；XXL-Job 扫描 + CAS 抢占投递）
 *
 * 按业务记录执行后台任务，不依赖租户上下文。
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_event_outbox", autoResultMap = true)
@KeySequence("dh_dataforge_fill_event_outbox_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillEventOutboxDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 全局事件号（UUID）
     */
    private String eventId;

    /**
     * 事件类型，见 FillEventTypeEnum
     */
    private String eventType;

    /**
     * 业务对象类型：SUBMISSION/WORK_ITEM/MERGE_BATCH/TASK
     */
    private String bizType;

    /**
     * 业务对象编号
     */
    private Long bizId;

    /**
     * 事件负载 JSON
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String payload;

    /**
     * 状态：0-待发送 1-发送中 2-成功 3-失败，见 FillOutboxStatusEnum
     */
    private Integer status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryTime;

    /**
     * 失败原因
     */
    private String errorMessage;

}
