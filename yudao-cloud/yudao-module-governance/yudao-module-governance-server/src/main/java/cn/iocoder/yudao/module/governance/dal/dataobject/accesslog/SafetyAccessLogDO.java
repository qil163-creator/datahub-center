package cn.iocoder.yudao.module.governance.dal.dataobject.accesslog;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 敏感数据访问审计 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_access_log")
@KeySequence("dh_safety_access_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyAccessLogDO extends BaseDO {

    /** 访问类型：数据服务 API */
    public static final String ACCESS_TYPE_API = "API";

    /** 访问类型：静态脱敏任务 */
    public static final String ACCESS_TYPE_STATIC_TASK = "STATIC_TASK";

    /** 访问类型：数据查询工作台 */
    public static final String ACCESS_TYPE_QUERY = "QUERY";

    /** 已脱敏 */
    public static final Integer MASKED_YES = 1;

    /** 明文（白名单豁免） */
    public static final Integer MASKED_NO = 0;

    /**
     * 日志编号
     */
    @TableId
    private Long id;

    /**
     * 访问用户编号（管理端用户，网关应用调用时为空）
     */
    private Long userId;

    /**
     * 访问用户名称
     */
    private String userName;

    /**
     * 调用应用编号（数据服务网关调用时记录）
     */
    private Long appId;

    /**
     * 访问类型：API / STATIC_TASK
     */
    private String accessType;

    /**
     * 资源名称（API 名称 / 任务名称）
     */
    private String resourceName;

    /**
     * 数据源编号
     */
    private Long databaseId;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 涉及的敏感字段（逗号分隔）
     */
    private String sensitiveColumns;

    /**
     * 敏感字段数
     */
    private Integer columnCount;

    /**
     * 是否已脱敏：1-已脱敏 0-明文（白名单豁免）
     */
    private Integer maskedFlag;

    /**
     * 命中的白名单编号（豁免时记录）
     */
    private Long whitelistId;

    /**
     * 调用方 IP
     */
    private String callerIp;

    /**
     * 访问时间
     */
    private LocalDateTime accessTime;

}
