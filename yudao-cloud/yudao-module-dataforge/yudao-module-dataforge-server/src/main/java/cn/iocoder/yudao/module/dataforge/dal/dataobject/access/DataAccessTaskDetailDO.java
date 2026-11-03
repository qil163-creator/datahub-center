package cn.iocoder.yudao.module.dataforge.dal.dataobject.access;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import lombok.*;

/**
 * 数据同步任务-表级同步结果 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_access_task_detail", autoResultMap = true)
@KeySequence("dh_dataforge_access_task_detail_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAccessTaskDetailDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 数据同步任务id
     */
    private Long dataAccessId;

    /**
     * 执行记录id
     */
    private Long taskId;

    /**
     * 源端 schema
     */
    private String sourceSchemaName;

    /**
     * 源端表名
     */
    private String sourceTableName;

    /**
     * 目的端 schema
     */
    private String targetSchemaName;

    /**
     * 目的端表名
     */
    private String targetTableName;

    /**
     * 同步记录数
     */
    private Long syncCount;

    /**
     * 同步数据量（人性化格式）
     */
    private String syncBytes;

    /**
     * 是否成功：0-否 1-是
     */
    private Integer ifSuccess;

    /**
     * 失败信息
     */
    private String errorMsg;

    /**
     * 成功信息
     */
    private String successMsg;

    /** 有版本的领域类型映射诊断，仅含元数据；历史记录为空。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String typeMappingPlan;

    /**
     * 单位id
     */
    private Long orgId;

}
