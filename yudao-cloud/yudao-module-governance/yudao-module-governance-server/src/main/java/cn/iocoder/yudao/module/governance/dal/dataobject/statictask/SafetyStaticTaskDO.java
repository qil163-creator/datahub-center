package cn.iocoder.yudao.module.governance.dal.dataobject.statictask;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 静态脱敏任务 DO：把源库表数据按字段脱敏规则批量处理后写入目标库表
 * （生产数据脱敏后落地到开发/测试环境等场景）
 *
 * @author Li
 */
@TableName(value = "dh_governance_safety_static_task", autoResultMap = true)
@KeySequence("dh_safety_static_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyStaticTaskDO extends BaseDO {

    /** 写入模式：全量覆盖（先清空目标表） */
    public static final String WRITE_MODE_OVERWRITE = "OVERWRITE";

    /** 写入模式：追加 */
    public static final String WRITE_MODE_APPEND = "APPEND";

    /** 状态：上线（启用） */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态：下线（停用） */
    public static final Integer STATUS_DISABLE = 1;

    /**
     * 任务编号
     */
    @TableId
    private Long id;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 源数据源编号（dataforge 数据源，关联 dh_database_info.id）
     */
    private Long sourceDatabaseId;

    /**
     * 源表名
     */
    private String sourceTableName;

    /**
     * 目标数据源编号
     */
    private Long targetDatabaseId;

    /**
     * 目标表名（需已存在，且包含源表全部字段）
     */
    private String targetTableName;

    /**
     * 写入模式：OVERWRITE-全量覆盖（先清空目标表）、APPEND-追加
     */
    private String writeMode;

    /**
     * 批次大小（分批读取/写入的行数）
     */
    private Integer batchSize;

    /**
     * 字段脱敏配置，JSON 数组：[{"columnName":"mobile","ruleId":1}, ...]；
     * 未配置的字段原样复制
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String fieldRulesJson;

    /**
     * 定时执行 Cron 表达式，为空表示仅手动执行
     */
    private String cronExpression;

    /**
     * 状态：0-上线、1-下线（下线任务不参与调度）
     */
    private Integer status;

    /**
     * 任务描述
     */
    private String description;

}
