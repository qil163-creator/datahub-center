package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主数据领域持久记录；通过业务对象标识关联。 */
@TableName("dh_governance_master_code_counter")
@KeySequence("dh_governance_master_code_counter_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterCodeCounterDO extends BaseDO {

    private Long id;

    private Long modelId;

    private Long nextValue;
}
