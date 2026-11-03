package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 在线填报-草稿操作应用 Response VO（即 ACK）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 草稿操作应用 Response VO")
@Data
public class FillOperationApplyRespVO {

    @Schema(description = "应用后的服务端版本", example = "138")
    private Long serverVersion;

    @Schema(description = "本次实际应用的操作数", example = "3")
    private Integer appliedCount;

    @Schema(description = "重复被忽略的操作数（clientOpId 已存在）", example = "0")
    private Integer duplicateCount;

    @Schema(description = "应用后的草稿行数", example = "120")
    private Integer draftRowCount;

}
