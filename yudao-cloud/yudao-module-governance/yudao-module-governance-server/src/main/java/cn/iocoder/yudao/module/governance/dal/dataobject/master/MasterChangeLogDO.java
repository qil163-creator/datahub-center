package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据领域持久记录；通过业务对象标识关联。 */
@TableName("dh_governance_master_change_log")
@KeySequence("dh_governance_master_change_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterChangeLogDO extends BaseDO {

    private Long id;

    private Long changeId;

    private Long modelId;

    private Integer rowOrdinal;

    private String dataId;

    private String changeType;

    private String propertyName;

    private String beforeValue;

    private String afterValue;

    private Long changedBy;

    private LocalDateTime changedAt;
}
