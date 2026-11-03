package cn.iocoder.yudao.module.governance.controller.admin.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 标准文件替代候选精简 Response VO")
@Data
public class StandardDocSimpleRespVO {

    @Schema(description = "标准文件编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "标准编号")
    private String stdCode;

    @Schema(description = "标准名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nameCn;

    @Schema(description = "标准分类", example = "NATIONAL")
    private String stdType;

    @Schema(description = "入库编号")
    private String registryNo;

    @Schema(description = "实施状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "CURRENT")
    private String implementStatus;

}
