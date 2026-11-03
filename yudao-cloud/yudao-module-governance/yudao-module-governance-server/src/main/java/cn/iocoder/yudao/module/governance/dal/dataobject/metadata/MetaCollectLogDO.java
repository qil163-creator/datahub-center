package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-元数据采集实例日志 DO
 *
 * 结构化日志：stage + object_ref 前缀（对齐 qData 日志风格），由采集引擎分批落库
 *
 * @author Li
 */
@TableName("dh_governance_meta_collect_log")
@KeySequence("dh_governance_meta_collect_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaCollectLogDO extends BaseDO {

    /** 日志级别：INFO */
    public static final String LEVEL_INFO = "INFO";

    /** 日志级别：WARN */
    public static final String LEVEL_WARN = "WARN";

    /** 日志级别：ERROR */
    public static final String LEVEL_ERROR = "ERROR";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 采集实例编号
     */
    private Long instanceId;

    /**
     * 采集任务编号
     */
    private Long taskId;

    /**
     * 日志时间（毫秒）
     */
    private LocalDateTime logTime;

    /**
     * 日志级别：INFO/WARN/ERROR
     */
    private String logLevel;

    /**
     * 阶段：CONNECT/READ/DIFF/WRITE/DONE
     */
    private String stage;

    /**
     * 对象引用，如 [TABLE] dwd.dwd_dim_station
     */
    private String objectRef;

    /**
     * 日志内容
     */
    private String content;

    /**
     * 备注
     */
    private String remark;

}
