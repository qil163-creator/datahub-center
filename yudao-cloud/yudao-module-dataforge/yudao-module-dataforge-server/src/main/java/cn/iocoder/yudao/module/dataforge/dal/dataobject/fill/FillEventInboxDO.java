package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-外部事件收件箱 DO（回调只做校验+落库+返回，重逻辑异步；重复/乱序事件只记录不执行副作用）
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_event_inbox", autoResultMap = true)
@KeySequence("dh_dataforge_fill_event_inbox_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillEventInboxDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 幂等去重键；BPM 事件无全局 eventId，用 {processInstanceId}:{status} 确定性构造
     */
    private String eventId;

    /**
     * 事件类型，见 FillEventTypeEnum
     */
    private String eventType;

    /**
     * 事件来源：BPM 等
     */
    private String source;

    /**
     * 事件负载 JSON
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String payload;

    /**
     * 状态：0-已接收 1-已处理 2-已忽略 3-处理失败，见 FillInboxStatusEnum
     */
    private Integer status;

    /**
     * 处理结果说明
     */
    private String processResult;

}
