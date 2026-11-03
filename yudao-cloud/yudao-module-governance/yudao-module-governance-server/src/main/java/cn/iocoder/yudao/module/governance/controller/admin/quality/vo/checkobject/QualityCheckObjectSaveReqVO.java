package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 检查对象新增/修改 Request VO")
@Data
public class QualityCheckObjectSaveReqVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "检查对象名称")
    @NotBlank(message = "检查对象名称不能为空")
    @Size(max = 128, message = "检查对象名称长度不能超过 128")
    private String objectName;

    @Schema(description = "数据源连接ID")
    @NotNull(message = "数据源连接不能为空")
    private Long datasourceConnId;

    @Schema(description = "数据连接类型")
    @Size(max = 32)
    private String datasourceType;

    @Schema(description = "数据连接实例")
    @Size(max = 128)
    private String datasourceInstance;

    @Schema(description = "格式名称")
    @Size(max = 64)
    private String formatName;

    @Schema(description = "表名称")
    @NotBlank(message = "表名称不能为空")
    @Size(max = 128, message = "表名称长度不能超过 128")
    private String tableName;

    @Schema(description = "关联元数据表ID")
    private Long tableId;

    @Schema(description = "备注")
    @Size(max = 512)
    private String remark;

}
