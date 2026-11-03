package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 显式申请清理本人已发布文件与身份锚点，完成后不可恢复")
public class EtlFileRetireReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotNull
    @Schema(description = "清理时间，按全局契约传毫秒时间戳，必须在数据库当前时间一分钟至九十天之后")
    private LocalDateTime retireAfter;

    @NotBlank
    @Size(max = 500)
    private String reason;

    @NotNull
    @AssertTrue
    @Schema(description = "必须明确确认不可恢复删除", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean confirmDeletion;
}
