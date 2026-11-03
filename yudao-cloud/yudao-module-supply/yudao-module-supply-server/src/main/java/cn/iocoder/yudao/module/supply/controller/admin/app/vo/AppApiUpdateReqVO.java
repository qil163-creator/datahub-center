package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "管理后台 - 普通独立管理员 API 授权修改 Request VO")
@Data
public class AppApiUpdateReqVO {

    @Schema(description = "授权编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "授权编号不能为空")
    @Min(value = 1, message = "授权编号必须为正数")
    private Long id;

    @Schema(description = "最近读取的聚合控制版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "授权控制版本不能为空")
    @Min(value = 0, message = "授权控制版本不能为负数")
    private Long expectedRevision;

    @Schema(description = "是否永久有效：0-否 1-是", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "是否永久有效不能为空")
    private Integer foreverFlag;

    @Schema(description = "有效期开始日期")
    private LocalDate startDate;

    @Schema(description = "有效期结束日期；非永久授权必须填写")
    private LocalDate endDate;

    @Schema(description = "授权描述；空值表示清空")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;
}
