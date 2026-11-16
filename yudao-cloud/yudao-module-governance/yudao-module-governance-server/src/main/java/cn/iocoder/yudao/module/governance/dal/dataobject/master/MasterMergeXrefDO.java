package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据合并来源追溯：被合并记录合并前的内容及其归入的黄金记录。 */
@TableName("dh_governance_master_merge_xref")
@KeySequence("dh_governance_master_merge_xref_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterMergeXrefDO extends BaseDO {

    private Long id;

    private Long modelId;

    private Long changeId;

    private Long groupId;

    /** 黄金记录主键标识（与变更日志的数据标识一致） */
    private String goldenKey;

    /** 被合并记录主键标识 */
    private String sourceKey;

    /** 被合并记录合并前的完整内容 */
    private String sourceRowJson;

    private Long mergedBy;

    private LocalDateTime mergedAt;
}
