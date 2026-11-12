package cn.iocoder.yudao.module.asset.dal.dataobject.dataexport;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 数据查询结果导出申请。审批通过后在有效期内可下载一次；下载时重新执行查询并按导出场景脱敏，
 * 不保存导出文件和查询结果。
 */
@TableName("dh_asset_data_export")
@KeySequence("dh_asset_data_export_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DataExportRequestDO extends BaseDO {

    /** 审批中。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已批准，可下载。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已驳回。 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已撤回。 */
    public static final String STATUS_CANCELED = "CANCELED";
    /** 已下载（一次性）。 */
    public static final String STATUS_DOWNLOADED = "DOWNLOADED";
    /** 审批流程未能发起。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId
    private Long id;
    /** 申请人 */
    private Long userId;
    /** 数据源编号 */
    private Long datasourceId;
    /** 目标 schema（申请时校验得到） */
    private String schemaName;
    /** 目标表 */
    private String tableName;
    /** 查询 SQL（受限 SELECT；只在本表保存，不写日志） */
    private String sqlText;
    /** 导出行数上限 */
    private Integer rowLimit;
    /** 申请原因 */
    private String reason;
    /** 状态 */
    private String status;
    /** BPM 流程实例编号 */
    private String processInstanceId;
    /** 审批结果说明 */
    private String resultReason;
    /** 批准时间 */
    private LocalDateTime approvedTime;
    /** 下载截止时间 */
    private LocalDateTime expireTime;
    /** 下载时间 */
    private LocalDateTime downloadTime;
    /** 下载的行数 */
    private Integer downloadRows;
    /** 乐观锁修订号 */
    private Integer revision;
}
