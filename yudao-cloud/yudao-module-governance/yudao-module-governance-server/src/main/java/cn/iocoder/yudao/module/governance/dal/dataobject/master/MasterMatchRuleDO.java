package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 主数据相似匹配规则；每个模型一条，字段配置以 JSON 保存并在使用前按当前模型字段重新校验。 */
@TableName("dh_governance_master_match_rule")
@KeySequence("dh_governance_master_match_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterMatchRuleDO extends BaseDO {

    private Long id;

    private Long modelId;

    /** 参与打分的字段：[{name, algorithm, weight}] */
    private String fieldsJson;

    /** 分块字段名数组 */
    private String blockingFieldsJson;

    /** 疑似重复相似度阈值（0.5～1） */
    private BigDecimal threshold;

    /** 黄金记录字段取值规则：{字段名: 规则} */
    private String survivorshipJson;

    /** 单次匹配最大读取记录数 */
    private Integer maxRows;
}
