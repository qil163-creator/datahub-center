package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;

/**
 * 数据开发-任务版本 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_dev_task_version", autoResultMap = true)
@KeySequence("dh_dataforge_dev_task_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevTaskVersionDO extends BaseDO {

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshotJson;


    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 任务编号
     */
    private Long taskId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 版本脚本内容
     */
    private String content;

    /**
     * 版本备注
     */
    private String remark;

}
