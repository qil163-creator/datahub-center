package cn.iocoder.yudao.module.dataforge.dal.dataobject.access;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.AccessTaskConfigTypeHandler;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据同步任务 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_access", autoResultMap = true)
@KeySequence("dh_dataforge_access_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAccessDO extends BaseDO {

    /** 配置变更版本与发布快照。 */
    private Integer revision;

    private Integer publishedRevision;

    private Long publishedExecutionUserId;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String publishedConfigJson;

    private Long scheduleGeneration;

    private Long latestRunId;

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 描述
     */
    private String description;

    /**
     * 单位id
     */
    private Long orgId;

    /**
     * 同步引擎类型：1-离线同步(DBSwitch) 2-实时同步(Seatunnel) 3-API 接入
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessEngineTypeEnum}
     */
    private Integer engineType;

    /**
     * 源端类型：1-指定表 2-SQL
     *
     * 枚举 {@link cn.iocoder.yudao.framework.dbswitch.common.type.SourceType}
     */
    private Integer sourceType;

    /**
     * 源端数据源id（离线同步必填；实时同步与 API 接入可为空）
     */
    private Long sourceDatabaseId;

    /**
     * 目的端数据源id
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long targetDatabaseId;

    /**
     * 接入方式：1-入中台库 2-自定义接入
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessModeEnum}
     */
    private Integer accessMode;

    /**
     * 任务类型：2-一次性全量同步 3-一次全量周期增量
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessTaskTypeEnum}
     */
    private Integer taskType;

    /**
     * cron 表达式（周期任务）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cron;

    /**
     * 发布状态：0-未发布 1-已发布
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessReleaseStatusEnum}
     */
    private Integer status;

    /**
     * 最新运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessRunStatusEnum}
     */
    private Integer runStatus;

    /**
     * 同步配置（dbswitch 属性，连接信息运行时填充，不落库）
     */
    @TableField(typeHandler = AccessTaskConfigTypeHandler.class)
    private AccessTaskConfig dataAccessJson;

    /**
     * SeaTunnel 原生作业配置（engine_type=2 时使用）
     */
    @lombok.ToString.Exclude
    private String seatunnelConfig;

    /**
     * SeaTunnel 配置格式：json / hocon
     */
    private String configFormat;

    /**
     * API 接入配置（engine_type=3 时使用：写入选项 + API/目标表/字段映射）
     */
    @TableField(typeHandler = cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler.class)
    @lombok.ToString.Exclude
    private String apiAccessJson;

    /**
     * 最近开始时间
     */
    private LocalDateTime startTime;

    /**
     * 最近结束时间
     */
    private LocalDateTime endTime;

    /**
     * 发布时间
     */
    private LocalDateTime releaseTime;

    /**
     * 发布人id
     */
    private Long releaseUserId;

    /**
     * 下次执行时间（周期任务）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRunTime;

    /**
     * 备注
     */
    private String remark;

}
