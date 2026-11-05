package cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据填报-填报表字段 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_table_column")
@KeySequence("dh_dataforge_fill_table_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTableColumnDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 填报表id
     */
    private Long tableId;

    /**
     * 物理字段名
     */
    private String name;

    /**
     * 中文名称（录入页面表头）
     */
    private String cnName;

    /**
     * 物理列注释
     */
    private String commentStr;

    /**
     * 数据类型（dbswitch 逻辑类型）：1-小数 2-字符串 3-日期 5-整数 6-长整型 9-日期时间 10-时间
     *
     * 见 {@link cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData} 的 TYPE_* 常量
     */
    private Integer fieldType;

    /**
     * 录入类型：1-手工录入 2-选择
     */
    private Integer fillType;

    /**
     * 选择值（英文逗号分隔，fillType=2 时使用）
     */
    private String fillValues;

    /**
     * 录入是否隐藏：0-否 1-是
     */
    private Integer fillHide;

    /**
     * 字段长度
     */
    private Integer fieldLength;

    /**
     * 小数位数
     */
    private Integer fieldScale;

    /**
     * 是否可为空：0-否 1-是
     */
    private Integer nullable;

    /**
     * 是否查询条件：0-否 1-是
     */
    private Integer ifQuery;

    /**
     * 是否主键：0-否 1-是
     */
    private Integer pk;

    /**
     * 主键类型：1-UUID（应用生成 32 位字符串） 2-自行录入
     */
    private Integer pkType;

    /**
     * 字段顺序（建表与页面展示顺序）
     */
    private Integer sort;

    /**
     * 永久不可变字段码；创建时取字段名，后续改名不变更，
     * 公式/操作日志/校验规则/来源追踪只引用 fieldCode
     */
    private String fieldCode;

    /**
     * 最小值（数值或日期时间，按字段类型比较；空表示不限）
     */
    private String minValue;

    /**
     * 最大值（数值或日期时间，按字段类型比较；空表示不限）
     */
    private String maxValue;

    /**
     * 格式正则（仅字符串字段，整体匹配）
     */
    private String regexPattern;

    /**
     * 格式不符时的提示语
     */
    private String regexMessage;

    /**
     * 同一次填报内该字段取值是否必须唯一：0-否 1-是
     */
    private Integer uniqueFlag;

    /**
     * 范围、格式、唯一规则的级别：1-错误（阻断提交） 2-警告
     */
    private Integer ruleSeverity;

    /**
     * 选择值来源的系统字典类型；设置后发布时固化字典项，优先于 fillValues
     */
    private String dictType;

    /**
     * 转换为 dbswitch 的列描述，用于生成建表 DDL
     *
     * 注意：UUID 主键由应用生成字符串，不映射为数据库自增（修正 srt-cloud 的问题）
     */
    public ColumnDescription toColumnDescription() {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setLabelName(name);
        description.setValType(fieldType);
        description.setLength(fieldLength);
        description.setPrecision(fieldScale);
        description.setNullable(nullable == null || nullable == 1);
        description.setRemarks(commentStr);
        description.setPk(pk != null && pk == 1);
        description.setAutoIncrement(false);
        return description;
    }

}
