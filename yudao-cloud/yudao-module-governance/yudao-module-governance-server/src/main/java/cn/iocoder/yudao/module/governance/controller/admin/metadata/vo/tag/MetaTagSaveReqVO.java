package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 元数据标签新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据标签新增/修改 Request VO")
@Data
public class MetaTagSaveReqVO {

    @Schema(description = "标签编号（修改时必填）", example = "1")
    private Long id;

    @Schema(description = "标签名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "核心表")
    @NotEmpty(message = "标签名称不能为空")
    @Size(max = 64, message = "标签名称长度不能超过 64")
    private String name;

    @Schema(description = "标签分类", example = "治理")
    @Size(max = 64, message = "标签分类长度不能超过 64")
    private String category;

    @Schema(description = "标签颜色", example = "#1677ff")
    @Size(max = 32, message = "标签颜色长度不能超过 32")
    private String color;

    @Schema(description = "备注")
    @Size(max = 512, message = "备注长度不能超过 512")
    private String remark;

}
