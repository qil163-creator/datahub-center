package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 任务实例日志分页请求；仅接受平台实例编号")
public class DevTaskLogPageReqVO {

    @NotNull
    @Positive
    @Schema(description = "平台运行实例编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @NotNull
    @Min(0)
    @Max(Integer.MAX_VALUE - 1000)
    @Schema(description = "跳过的物理日志行数", example = "0")
    private Integer skipLineNum = 0;

    @NotNull
    @Min(1)
    @Max(1000)
    @Schema(description = "本页最多读取的行数", example = "200")
    private Integer limit = 200;
}
