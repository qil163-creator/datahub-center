package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 主数据疑似重复组；成员保存主键与匹配时的记录快照，合并通过主数据变更申请生效。 */
@TableName("dh_governance_master_match_group")
@KeySequence("dh_governance_master_match_group_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterMatchGroupDO extends BaseDO {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_MERGING = "MERGING";
    public static final String STATUS_MERGED = "MERGED";
    public static final String STATUS_IGNORED = "IGNORED";
    public static final String STATUS_STALE = "STALE";

    private Long id;

    private Long runId;

    private Long modelId;

    /** 成员主键集合摘要 */
    private String fingerprint;

    private String status;

    private Integer memberCount;

    private BigDecimal maxScore;

    /** 成员：[{key, keyValues, row}] */
    private String membersJson;

    /** 记录对：[{left, right, score, fields}] */
    private String pairsJson;

    /** 合并提交的变更申请编号 */
    private Long changeId;

    private Long handledBy;

    private LocalDateTime handledAt;

    private String handleRemark;
}
