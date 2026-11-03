package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - 名称映射预览 Response VO")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewNameMapperRespVO {

    @Schema(description = "原名称", example = "t_user")
    private String originalName;

    @Schema(description = "映射后名称（<!空> 表示映射为空，<!删除> 表示该字段不同步）", example = "ods_t_user")
    private String targetName;

    @Schema(description = "注释", example = "用户表")
    private String remarks;

}
