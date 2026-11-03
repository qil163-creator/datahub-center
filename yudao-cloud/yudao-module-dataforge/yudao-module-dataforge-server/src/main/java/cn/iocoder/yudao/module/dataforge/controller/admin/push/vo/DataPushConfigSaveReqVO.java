package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据推送配置保存 Request VO")
@Data
public class DataPushConfigSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "配置名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "公安 HTTP 接口")
    @NotBlank(message = "配置名称不能为空")
    @Size(max = 128, message = "配置名称不能超过 128 个字符")
    private String name;

    @Schema(description = "通道类型：1-HTTP 2-数据库 3-消息队列", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "通道类型不能为空")
    private Integer channelType;

    @Schema(description = "通道配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "通道配置不能为空")
    @Valid
    private DataPushChannelConfigVO config;

    @Schema(description = "说明")
    @Size(max = 500, message = "说明不能超过 500 个字符")
    private String description;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

}
