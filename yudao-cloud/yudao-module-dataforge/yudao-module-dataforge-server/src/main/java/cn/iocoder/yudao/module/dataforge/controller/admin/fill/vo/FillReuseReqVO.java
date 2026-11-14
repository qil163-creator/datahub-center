package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 在线填报-复用历史数据 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 复用历史填报数据 Request VO")
@Data
public class FillReuseReqVO {

    @Schema(description = "来源提交快照编号（取自可复用列表）", requiredMode = Schema.RequiredMode.REQUIRED, example = "71")
    @NotNull(message = "请选择要复用的期次")
    private Long submissionId;

    @Schema(description = "方式：1-追加到现有草稿 2-替换全部草稿行", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "复用方式不能为空")
    @Min(value = 1, message = "复用方式不合法")
    @Max(value = 2, message = "复用方式不合法")
    private Integer mode;

    @Schema(description = "客户端已同步到的服务端版本（并发编辑冲突检测）", requiredMode = Schema.RequiredMode.REQUIRED, example = "135")
    @NotNull(message = "expectedVersion 不能为空")
    private Long expectedVersion;

}
