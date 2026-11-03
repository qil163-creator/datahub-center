package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-元数据采集实例 DO
 *
 * 一次采集执行的事实记录：阶段、错误分类、断点游标与差异统计；
 * 失败保存 stage + error_type + cursor，重试按游标断点续跑，不覆盖上次成功快照
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_collect_instance", autoResultMap = true)
@KeySequence("dh_governance_meta_collect_instance_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaCollectInstanceDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;

    private String taskName;

    private Long datasourceId;

    private String datasourceName;

    private Long sourceSystemId;

    private String sourceSystemName;

    private Integer collectType;

    private Long ownerUserId;

    private String ownerName;

    private String ownerPhone;

    private String description;

    private Integer fileCount;

    public static final int STATUS_QUEUED = 0;

    public static final int STATUS_DISCARDED = 5;

    private String executionToken;

    private LocalDateTime leaseUntil;

    private Integer attemptCount;

    private Long executionUserId;


    /** 触发方式：调度 */
    public static final int TRIGGER_SCHEDULE = 1;

    /** 触发方式：手动 */
    public static final int TRIGGER_MANUAL = 2;

    /** 触发方式：事件 */
    public static final int TRIGGER_EVENT = 3;

    /** 触发方式：Excel 导入 */
    public static final int TRIGGER_IMPORT = 4;

    /** 触发方式：手工登记 */
    public static final int TRIGGER_REGISTER = 5;

    /** 状态：运行中 */
    public static final int STATUS_RUNNING = 1;

    /** 状态：成功 */
    public static final int STATUS_SUCCESS = 2;

    /** 状态：失败 */
    public static final int STATUS_FAIL = 3;

    /** 状态：部分成功 */
    public static final int STATUS_PARTIAL = 4;

    /** 阶段：连接 */
    public static final String STAGE_CONNECT = "CONNECT";

    /** 阶段：读取 */
    public static final String STAGE_READ = "READ";

    /** 阶段：比对 */
    public static final String STAGE_DIFF = "DIFF";

    /** 阶段：写入 */
    public static final String STAGE_WRITE = "WRITE";

    /** 阶段：完成 */
    public static final String STAGE_DONE = "DONE";

    /** 错误分类：连接失败 */
    public static final String ERROR_CONNECT_FAIL = "CONNECT_FAIL";

    /** 错误分类：超时 */
    public static final String ERROR_TIMEOUT = "TIMEOUT";

    /** 错误分类：认证失败 */
    public static final String ERROR_AUTH = "AUTH";

    /** 错误分类：解析失败 */
    public static final String ERROR_PARSE = "PARSE";

    /** 错误分类：部分对象失败 */
    public static final String ERROR_PARTIAL = "PARTIAL";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 采集任务编号（导入/手工登记的虚拟实例可空）
     */
    private Long taskId;

    /**
     * 触发方式：1-调度 2-手动 3-事件 4-导入 5-手工登记
     */
    private Integer triggerType;

    /**
     * 状态：1-运行中 2-成功 3-失败 4-部分成功
     */
    private Integer status;

    /**
     * 阶段：CONNECT/READ/DIFF/WRITE/DONE（失败停留在失败阶段）
     */
    private String stage;

    /**
     * 错误分类：CONNECT_FAIL/TIMEOUT/AUTH/PARSE/PARTIAL 等
     */
    private String errorType;

    /**
     * 断点游标 JSON：{"finishedDbs":["dwd"],"currentDb":"ods"}
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String collectCursor;

    /**
     * 处理库数量
     */
    private Integer dbCount;

    /**
     * 处理表数量
     */
    private Integer tableCount;

    /**
     * 处理字段数量
     */
    private Integer columnCount;

    /**
     * 表新增数
     */
    private Integer tableAdd;

    /**
     * 表变更数
     */
    private Integer tableUpdate;

    /**
     * 表待删除数（进入待确认）
     */
    private Integer tableDelete;

    /**
     * 字段新增数
     */
    private Integer columnAdd;

    /**
     * 字段变更数
     */
    private Integer columnUpdate;

    /**
     * 字段删除数
     */
    private Integer columnDelete;

    /**
     * 失败对象清单 JSON：[{"db":"ods","table":"t1","error":"..."}]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String failDetail;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 汇总摘要 JSON（事件载荷与概览复用）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String report;

    /**
     * 备注
     */
    private String remark;

}
