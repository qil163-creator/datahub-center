package cn.iocoder.yudao.module.governance.dal.dataobject.category;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据分类信息 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_category")
@KeySequence("dh_safety_category_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyCategoryDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 所属类目ID
     */
    private Long categoryId;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 分类缩写
     */
    private String shortName;

    /**
     * 数据分级
     */
    private Integer dataLevelId;

    /**
     * 优先级
     */
    private String priority;

    /**
     * 脱敏配置
     */
    private Long ruleId;

    /**
     * 备注
     */
    private String remark;

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

    /** 本地并发版本；来源显示信息不替代 BaseDO 审计。 */
    private Long revision;

    private String sourceRecordId;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private LocalDateTime sourceCreateTime;

    private LocalDateTime sourceUpdateTime;


}
