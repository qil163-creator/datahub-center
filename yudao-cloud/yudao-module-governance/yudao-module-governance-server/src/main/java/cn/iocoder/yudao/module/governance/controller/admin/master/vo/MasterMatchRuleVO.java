package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 主数据相似匹配规则（保存请求与查询结果）")
@Data
public class MasterMatchRuleVO {

    @Schema(description = "主数据模型编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "模型编号不能为空")
    private Long modelId;

    @Schema(description = "参与打分的字段")
    @NotNull(message = "请选择参与匹配的字段")
    @Size(min = 1, max = 20, message = "参与匹配的字段为1至20个")
    @Valid
    private List<Field> fields;

    @Schema(description = "分块字段：取值相同的记录才互相比较")
    @Size(max = 5, message = "分块字段最多5个")
    private List<String> blockingFields;

    @Schema(description = "疑似重复相似度阈值，0.5～1 的十进制字符串", example = "0.85")
    @NotBlank(message = "请填写相似度阈值")
    @Pattern(regexp = "(0(\\.\\d{1,4})?|1(\\.0{1,4})?)", message = "相似度阈值为0.5至1、最多4位小数")
    private String threshold;

    @Schema(description = "黄金记录字段取值规则：字段名 → SURVIVOR/MOST_COMPLETE/MOST_FREQUENT/MAX/MIN")
    private Map<String, String> survivorship;

    @Schema(description = "单次匹配最大读取记录数", example = "20000")
    @Min(value = 2, message = "最大读取记录数不能小于2")
    @Max(value = 100000, message = "最大读取记录数不能超过100000")
    private Integer maxRows;

    @Schema(description = "是否已保存过规则（仅查询结果）")
    private Boolean configured;

    @Schema(description = "规则更新时间（仅查询结果）")
    private LocalDateTime updateTime;

    @Data
    public static class Field {

        @NotBlank(message = "字段名不能为空")
        private String name;

        @Schema(description = "比较算法：EXACT 精确、TEXT 文本相似、EDIT 编辑距离、NUMERIC 数值接近")
        @NotBlank(message = "请选择比较算法")
        @Pattern(regexp = "EXACT|TEXT|EDIT|NUMERIC", message = "比较算法不正确")
        private String algorithm;

        @Schema(description = "权重 1～10")
        @NotNull(message = "请填写权重")
        @Min(value = 1, message = "权重为1至10")
        @Max(value = 10, message = "权重为1至10")
        private Integer weight;
    }
}
