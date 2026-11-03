package cn.iocoder.yudao.module.infra.controller.admin.file.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 上传并完成文件登记 Response VO")
@Data
public class FileUploadRespVO {

    @Schema(description = "已登记文件编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "文件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "文件访问地址", requiredMode = Schema.RequiredMode.REQUIRED)
    private String url;

}
