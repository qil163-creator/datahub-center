package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Schema(description = "管理后台 - 主数据记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDataPageReqVO extends PageParam {

    @Schema(description = "主数据模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "主数据模型编号不能为空")
    private Long modelId;

    @Schema(description = "查询条件（全部条件以 AND 连接，最多 50 项）")
    @Valid
    @Size(max = 50, message = "查询条件不能超过50项")
    private List<@NotNull(message = "查询条件项不能为空") Condition> conditions;

    @Schema(description = "查询条件项")
    @Data
    public static class Condition {

        @Schema(description = "字段名", example = "person_name")
        @Size(max = 255, message = "查询字段名不能超过255个字符")
        private String name;

        @Schema(description = "操作符；不传时兼容旧条件（字符串模糊匹配、其他精确匹配）",
                example = "CONTAINS", allowableValues = {"EQ", "NE", "GT", "GE", "LT", "LE", "CONTAINS",
                "NOT_CONTAINS", "IS_NULL", "IS_NOT_NULL", "IS_EMPTY", "IS_NOT_EMPTY"})
        @Size(max = 32, message = "查询操作符不能超过32个字符")
        private String operator;

        @Schema(description = "查询值；为空/非空/null/非null 操作符无需传值，整数和精确小数使用十进制字符串", example = "张三")
        private String value;

    }

}
