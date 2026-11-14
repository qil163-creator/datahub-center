package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 填报表字段 Response VO")
@Data
public class FillTableColumnRespVO {

    @Schema(description = "主键id", example = "1")
    private Long id;

    @Schema(description = "填报表id", example = "1024")
    private Long tableId;

    @Schema(description = "物理字段名", example = "id_card")
    private String name;

    @Schema(description = "中文名称", example = "身份证号")
    private String cnName;

    @Schema(description = "物理列注释")
    private String commentStr;

    @Schema(description = "数据类型：1-小数 2-字符串 3-日期 5-整数 6-长整型 9-日期时间 10-时间", example = "2")
    private Integer fieldType;

    @Schema(description = "录入类型：1-手工录入 2-选择 3-多选 4-人员 5-部门 6-附件 7-图片", example = "1")
    private Integer fillType;

    @Schema(description = "选择值（英文逗号分隔）", example = "男,女")
    private String fillValues;

    @Schema(description = "录入是否隐藏：0-否 1-是", example = "0")
    private Integer fillHide;

    @Schema(description = "字段长度", example = "50")
    private Integer fieldLength;

    @Schema(description = "小数位数", example = "2")
    private Integer fieldScale;

    @Schema(description = "是否可为空：0-否 1-是", example = "1")
    private Integer nullable;

    @Schema(description = "是否查询条件：0-否 1-是", example = "0")
    private Integer ifQuery;

    @Schema(description = "是否主键：0-否 1-是", example = "0")
    private Integer pk;

    @Schema(description = "主键类型：1-UUID 2-自行录入", example = "1")
    private Integer pkType;

    @Schema(description = "字段顺序", example = "1")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最小值（数值或日期时间）", example = "0")
    private String minValue;

    @Schema(description = "最大值（数值或日期时间）", example = "100")
    private String maxValue;

    @Schema(description = "格式正则（仅字符串字段，整体匹配）", example = "[0-9]{18}")
    private String regexPattern;

    @Schema(description = "格式不符时的提示语", example = "请输入 18 位数字")
    private String regexMessage;

    @Schema(description = "同一次填报内取值是否必须唯一：0-否 1-是", example = "0")
    private Integer uniqueFlag;

    @Schema(description = "规则级别：1-错误 2-警告", example = "1")
    private Integer ruleSeverity;

    @Schema(description = "选择值来源的系统字典类型", example = "common_status")
    private String dictType;

    @Schema(description = "行内公式", example = "unit_price * quantity")
    private String formulaExpr;

    @Schema(description = "录入页面列宽（像素）", example = "140")
    private Integer columnWidth;

    @Schema(description = "表头分组名称", example = "收入情况")
    private String headerGroup;

    @Schema(description = "数据表下拉：来源数据源编号", example = "2")
    private Long refDatabaseId;

    @Schema(description = "数据表下拉：来源表名", example = "md_region")
    private String refTableName;

    @Schema(description = "数据表下拉：取值列", example = "region_code")
    private String refValueColumn;

    @Schema(description = "级联：上级字段码", example = "province")
    private String refParentField;

    @Schema(description = "级联：来源表中对应上级取值的列", example = "province_code")
    private String refParentColumn;

    @Schema(description = "人员/部门选择范围：ORG-报送组织及下级 ALL-全部", example = "ORG")
    private String refScope;

    @Schema(description = "分组合计的计算方式：SUM MAX MIN COUNT FIRST，空表示不输出", example = "SUM")
    private String rollupFunc;

}
