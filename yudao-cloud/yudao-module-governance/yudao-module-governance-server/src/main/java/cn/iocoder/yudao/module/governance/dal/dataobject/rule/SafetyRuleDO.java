package cn.iocoder.yudao.module.governance.dal.dataobject.rule;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 脱敏规则（主表）；参数演进由版本表承载 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_rule")
@KeySequence("dh_safety_rule_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyRuleDO extends BaseDO {

    /**
     * 脱敏规则编号
     */
    @TableId
    private Long id;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 脱敏方式
     *
     * 枚举 {@link TODO method_code 对应的类}
     */
    private String methodCode;

    /**
     * 规则来源
     *
     * 枚举 {@link TODO source_type 对应的类}
     */
    private String sourceType;

    /**
     * 当前生效的规则
     */
    private Long currentVerId;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 显示顺序
     */
    private Integer sort;

    /**
     * 规则描述
     */
    private String description;

    private String sourceRecordId;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private LocalDateTime sourceCreateTime;

    private LocalDateTime sourceUpdateTime;


}
