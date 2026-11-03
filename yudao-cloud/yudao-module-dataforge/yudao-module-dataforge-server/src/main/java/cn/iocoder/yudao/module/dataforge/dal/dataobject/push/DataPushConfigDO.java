package cn.iocoder.yudao.module.dataforge.dal.dataobject.push;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据推送配置 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_push_config", autoResultMap = true)
@KeySequence("dh_dataforge_push_config_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataPushConfigDO extends BaseDO {

    @TableId
    private Long id;

    private String name;

    /**
     * 通道类型：1-HTTP 2-数据库 3-消息队列
     */
    private Integer channelType;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String channelJson;

    private String description;

    private Long orgId;

}
