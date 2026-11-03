package cn.iocoder.yudao.module.governance.dal.dataobject.identify;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 敏感字段打标 DO：数据源 + 表 + 字段 → 数据分类/分级
 *
 * @author Li
 */
@TableName("dh_governance_safety_field_tag")
@KeySequence("dh_safety_field_tag_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyFieldTagDO extends BaseDO {

    /** 打标来源：自动识别 */
    public static final String SOURCE_AUTO = "AUTO";

    /** 打标来源：人工添加 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 打标状态：待确认 */
    public static final String TAG_STATUS_PENDING = "PENDING";

    /** 打标状态：已确认 */
    public static final String TAG_STATUS_CONFIRMED = "CONFIRMED";

    /** 打标状态：已驳回 */
    public static final String TAG_STATUS_REJECTED = "REJECTED";

    /**
     * 打标编号
     */
    @TableId
    private Long id;

    /**
     * 数据源编号
     */
    private Long databaseId;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 字段名
     */
    private String columnName;

    /**
     * 数据分类编号
     */
    private Long categoryId;

    /**
     * 数据分级编号
     */
    private Long levelId;

    /**
     * 命中的识别规则编号（AUTO 来源时记录）
     */
    private Long identifyRuleId;

    /**
     * 产生本打标的扫描记录编号
     */
    private Long scanRunId;

    /**
     * 打标来源：AUTO / MANUAL
     */
    private String source;

    /**
     * 打标状态：PENDING / CONFIRMED / REJECTED
     */
    private String tagStatus;

    /**
     * 命中说明（规则名/匹配方式/采样命中率）
     */
    private String hitDesc;

    /**
     * 确认/驳回时间
     */
    private LocalDateTime confirmTime;

    /**
     * 备注
     */
    private String remark;

}
