package cn.iocoder.yudao.module.governance.dal.dataobject.level;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据分级 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_level")
@KeySequence("dh_safety_level_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyLevelDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 安全等级
     */
    private String safetyLevel;

    /**
     * 显示名称
     */
    private String safetyName;

    /**
     * 标志颜色（hex，如 #52c41a；为空时前端按等级使用默认配色）
     */
    private String color;

    /** 来源表单定义的敏感程度，范围 1 至 100。 */
    private Integer sensitiveLevel;

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


}
