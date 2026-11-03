package cn.iocoder.yudao.module.governance.dal.dataobject.identify;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 敏感数据扫描执行记录 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_scan_run")
@KeySequence("dh_safety_scan_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyScanRunDO extends BaseDO {

    /** 运行状态：运行中 */
    public static final Integer RUN_STATUS_RUNNING = 2;

    /** 运行状态：成功 */
    public static final Integer RUN_STATUS_SUCCESS = 3;

    /** 运行状态：失败 */
    public static final Integer RUN_STATUS_FAILED = 4;

    /**
     * 扫描记录编号
     */
    @TableId
    private Long id;

    /**
     * 扫描数据源编号
     */
    private Long databaseId;

    /**
     * 扫描表范围（逗号分隔表名，空=数据源下全部表）
     */
    private String tableScope;

    /**
     * 运行状态：2-运行中 3-成功 4-失败
     */
    private Integer runStatus;

    /**
     * 扫描表数
     */
    private Integer tableCount;

    /**
     * 扫描字段数
     */
    private Integer columnCount;

    /**
     * 新增待确认打标数
     */
    private Integer hitCount;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 失败信息
     */
    private String errorInfo;

}
