package cn.iocoder.yudao.module.governance.dal.dataobject.standardelement;

import lombok.*;
import com.baomidou.mybatisplus.annotation.*;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;

/**
 * 数据标准--标准数据元 DO
 *
 * @author Li
 */
@TableName("dh_governance_standard_element")
@KeySequence("dh_governance_standard_element_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardElementDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Integer id;

    /**
     * 数据元编码
     */
    private String code;

    /**
     * 中文名称
     */
    private String name;

    /**
     * 英文名称
     */
    private String engName;

    /**
     * 关联 dh_foundation_category_datameta.id
     */
    private Long categoryId;

    /**
     * 关联 dh_governance_standard_doc.id
     */
    private Long documentId;

    /**
     * 字段类型，如 VARCHAR、BIGINT
     */
    private String columnType;

    /**
     * 数据长度（字符/字节数上限）
     */
    private Integer dataLength;

    /**
     * 数据精度（数值总位数）
     */
    private Integer dataPrecision;

    /**
     * 小数位数
     */
    private Integer dataScale;

    /**
     * 数据格式，如 yyyy-MM-dd、\d{11}
     */
    private String dataFormat;

    /**
     * 计量单位，如 元、kg
     */
    private String measureUnit;

    /**
     * 值域范围说明，如 [0,150]、参见关联码表
     */
    private String valueRange;

    /**
     * 是否可为空：0-不可空 1-可空
     */
    private Integer nullableFlag;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 生命周期状态
     *
     * 枚举 {@link cn.iocoder.yudao.module.governance.enums.standard.StandardElementLifecycleEnum}
     */
    private String lifecycleStatus;

    /**
     * 关联标准码表 dh_governance_standard_code_set.id
     */
    private Integer codeSetId;

    /**
     * 负责人id
     */
    private Long personCharge;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;


    private String standardType;

    private String contactNumber;

    private Long revision;
}
