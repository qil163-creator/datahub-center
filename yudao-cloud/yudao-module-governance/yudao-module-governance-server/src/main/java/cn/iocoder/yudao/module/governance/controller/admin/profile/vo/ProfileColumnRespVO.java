package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 比率、平均值等精确小数一律以十进制字符串传输。
 */
@Schema(description = "管理后台 - 数据探查字段指标 Response VO")
@Data
public class ProfileColumnRespVO {

    @Schema(description = "字段指标编号")
    private Long id;

    @Schema(description = "元数据字段编号")
    private Long metaColumnId;

    @Schema(description = "字段名")
    private String columnName;

    @Schema(description = "字段类型")
    private String dataType;

    @Schema(description = "统计类别：TEXT/NUMERIC/TEMPORAL/BOOLEAN/OTHER")
    private String columnKind;

    @Schema(description = "字段序号")
    private Integer ordinalPosition;

    @Schema(description = "非空数")
    private Long nonNullCount;

    @Schema(description = "空值数")
    private Long nullCount;

    @Schema(description = "空值率，十进制字符串（0~1）", example = "0.012500")
    private String nullRate;

    @Schema(description = "唯一值数")
    private Long distinctCount;

    @Schema(description = "唯一率，十进制字符串（0~1）", example = "1.000000")
    private String uniqueRate;

    @Schema(description = "最小值；已脱敏时为空")
    private String minValue;

    @Schema(description = "最大值；已脱敏时为空")
    private String maxValue;

    @Schema(description = "取值是否已脱敏")
    private Boolean valueMasked;

    @Schema(description = "脱敏原因：RULE 命中分类规则、UNCLASSIFIED 敏感未分类、UNTRACEABLE 来源不可追溯、"
            + "UNVERIFIED 未验证、METADATA 元数据敏感标注、MASK_FAILED 脱敏服务失败（失败关闭）")
    private String maskReason;

    @Schema(description = "文本平均长度，十进制字符串")
    private String avgLength;

    @Schema(description = "文本最小长度")
    private Long minLength;

    @Schema(description = "文本最大长度")
    private Long maxLength;

    @Schema(description = "数值平均值，十进制字符串")
    private String avgValue;

    @Schema(description = "Top 值分布")
    private List<ProfileValueRespVO> topValues;

}
