package cn.iocoder.yudao.module.governance.dal.dataobject.profile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 数据探查-探查记录：每次对一张元数据表发起的字段级统计探查，带执行租约。
 *
 * @author Li
 */
@TableName("dh_governance_profile_run")
@KeySequence("dh_governance_profile_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProfileRunDO extends BaseDO {

    /** 等待执行 */
    public static final String STATUS_PENDING = "PENDING";
    /** 执行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 全部状态 */
    public static final Set<String> STATUSES = Set.of(STATUS_PENDING, STATUS_RUNNING, STATUS_SUCCESS, STATUS_FAILED);

    @TableId
    private Long id;

    /** 元数据表编号 */
    private Long metaTableId;

    /** 发起时的数据源编号快照 */
    private Long datasourceId;

    /** 发起时的物理 schema 快照 */
    private String schemaName;

    /** 发起时的物理表名快照 */
    private String tableName;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 抽样行数上限 */
    private Integer sampleLimit;

    /** 实际抽样行数 */
    private Long sampleRows;

    /** 全表总行数；统计失败时为空 */
    private Long totalRows;

    /** 实际探查字段数 */
    private Integer columnCount;

    /** 执行人 */
    private Long executorUserId;

    /** 首次开始执行时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 失败原因（不含 SQL 或连接信息） */
    private String failReason;

    /** 执行租约持有者令牌 */
    private String leaseOwner;

    /** 执行租约到期时间 */
    private LocalDateTime leaseUntil;

    /** 已领取执行次数 */
    private Integer attemptCount;

}
