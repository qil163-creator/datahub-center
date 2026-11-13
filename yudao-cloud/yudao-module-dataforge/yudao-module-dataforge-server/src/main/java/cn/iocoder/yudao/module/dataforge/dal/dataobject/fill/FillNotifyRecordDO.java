package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-通知与催办记录 DO（记录发送事实供看板与频控）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_notify_record")
@KeySequence("dh_dataforge_fill_notify_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillNotifyRecordDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 通知类型：1~11，见 FillNotifyTypeEnum
     */
    private Integer notifyType;

    /**
     * 周期任务编号
     */
    private Long taskId;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 接收人编号
     */
    private Long receiverUserId;

    /**
     * 渠道：1-站内信 2-短信 3-邮件，见 FillNotifyChannelEnum
     */
    private Integer channel;

    /**
     * 通知模板编号（system 站内信/短信模板 code）
     */
    private String templateCode;

    /**
     * 内容摘要
     */
    private String contentSummary;

    /**
     * 发送状态：0-待发送 1-已发送 2-发送失败
     */
    private Integer sendStatus;

    /**
     * 发送时间
     */
    private LocalDateTime sendTime;

    /**
     * 模板参数快照（JSON），用于发送失败后的重试
     */
    private String templateParams;

    /**
     * 已重试次数
     */
    private Integer retryCount;

}
