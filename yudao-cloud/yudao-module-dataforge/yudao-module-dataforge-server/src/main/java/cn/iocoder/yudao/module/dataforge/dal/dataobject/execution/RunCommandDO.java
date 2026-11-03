package cn.iocoder.yudao.module.dataforge.dal.dataobject.execution;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 已受理的控制命令回执；不原地改写或逻辑删除，以防旧请求作用于下一代执行。 */
@TableName(value = "dh_dataforge_run_command", autoResultMap = true)
@KeySequence("dh_dataforge_run_command_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunCommandDO extends BaseDO {

    @TableId
    private Long id;

    private String domain;

    private Long recordId;

    private String requestId;

    private String action;

    private String requestHash;

    private Long actorId;

    private Long expectedVersion;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String resultJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String beforeJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String afterJson;
}
