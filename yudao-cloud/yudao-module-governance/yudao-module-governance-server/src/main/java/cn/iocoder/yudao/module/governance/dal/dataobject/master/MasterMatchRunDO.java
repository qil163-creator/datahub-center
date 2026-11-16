package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据相似匹配执行记录。 */
@TableName("dh_governance_master_match_run")
@KeySequence("dh_governance_master_match_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterMatchRunDO extends BaseDO {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;

    private Long modelId;

    /** 执行时的规则快照 */
    private String ruleJson;

    private String status;

    private Integer scannedRows;

    private Long comparedPairs;

    private Integer groupCount;

    private String message;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long runBy;
}
