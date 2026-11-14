package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 填报表字段新增/修改 Request VO")
@Data
public class FillTableColumnSaveReqVO {

    @Schema(description = "主键id", example = "1")
    private Long id;

    @Schema(description = "填报表id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "填报表不能为空")
    private Long tableId;

    @Schema(description = "物理字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id_card")
    @NotEmpty(message = "字段名不能为空")
    private String name;

    @Schema(description = "中文名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "身份证号")
    @NotEmpty(message = "中文名称不能为空")
    private String cnName;

    @Schema(description = "物理列注释", example = "身份证号")
    private String commentStr;

    @Schema(description = "数据类型：1-小数 2-字符串 3-日期 5-整数 6-长整型 9-日期时间 10-时间",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "数据类型不能为空")
    private Integer fieldType;

    @Schema(description = "录入类型：1-手工录入 2-选择 3-多选（英文逗号分隔） 4-人员 5-部门 6-附件 7-图片", example = "1")
    @jakarta.validation.constraints.Min(value = 1, message = "录入类型不合法")
    @jakarta.validation.constraints.Max(value = 7, message = "录入类型不合法")
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

    @Schema(description = "最小值（数值或日期时间）", example = "0")
    @Size(max = 40, message = "最小值不能超过 40 个字符")
    private String minValue;

    @Schema(description = "最大值（数值或日期时间）", example = "100")
    @Size(max = 40, message = "最大值不能超过 40 个字符")
    private String maxValue;

    @Schema(description = "格式正则（仅字符串字段，整体匹配）", example = "[0-9]{18}")
    @Size(max = 200, message = "格式正则不能超过 200 个字符")
    private String regexPattern;

    @Schema(description = "格式不符时的提示语", example = "请输入 18 位数字")
    @Size(max = 100, message = "提示语不能超过 100 个字符")
    private String regexMessage;

    @Schema(description = "同一次填报内取值是否必须唯一：0-否 1-是", example = "0")
    private Integer uniqueFlag;

    @Schema(description = "规则级别：1-错误 2-警告", example = "1")
    private Integer ruleSeverity;

    @Schema(description = "选择值来源的系统字典类型", example = "common_status")
    @Size(max = 100, message = "字典类型不能超过 100 个字符")
    private String dictType;

    @Schema(description = "行内公式，例如 unit_price * quantity；设置后字段只读，由系统计算", example = "unit_price * quantity")
    @Size(max = 1000, message = "公式不能超过 1000 个字符")
    private String formulaExpr;

    @Schema(description = "录入页面列宽（像素，60~600）", example = "140")
    @jakarta.validation.constraints.Min(value = 60, message = "列宽不能小于 60")
    @jakarta.validation.constraints.Max(value = 600, message = "列宽不能大于 600")
    private Integer columnWidth;

    @Schema(description = "表头分组名称：相邻同名字段合并为上一级表头", example = "收入情况")
    @Size(max = 50, message = "表头分组名称不能超过 50 个字符")
    private String headerGroup;

    @Schema(description = "数据表下拉：来源数据源编号", example = "2")
    private Long refDatabaseId;

    @Schema(description = "数据表下拉：来源表名", example = "md_region")
    @Size(max = 128, message = "来源表名不能超过 128 个字符")
    private String refTableName;

    @Schema(description = "数据表下拉：取值列", example = "region_code")
    @Size(max = 128, message = "取值列不能超过 128 个字符")
    private String refValueColumn;

    @Schema(description = "级联：上级字段码", example = "province")
    @Size(max = 64, message = "上级字段码不能超过 64 个字符")
    private String refParentField;

    @Schema(description = "级联：来源表中对应上级取值的列", example = "province_code")
    @Size(max = 128, message = "上级取值列不能超过 128 个字符")
    private String refParentColumn;

    @Schema(description = "人员/部门选择范围：ORG-报送组织及下级 ALL-全部", example = "ORG")
    @jakarta.validation.constraints.Pattern(regexp = "^(|ORG|ALL)$", message = "选择范围不合法")
    private String refScope;

    @Schema(description = "分组合计的计算方式：SUM MAX MIN COUNT FIRST，空表示不输出", example = "SUM")
    @jakarta.validation.constraints.Pattern(regexp = "^(|SUM|MAX|MIN|COUNT|FIRST)$", message = "汇总计算方式不合法")
    private String rollupFunc;

}
