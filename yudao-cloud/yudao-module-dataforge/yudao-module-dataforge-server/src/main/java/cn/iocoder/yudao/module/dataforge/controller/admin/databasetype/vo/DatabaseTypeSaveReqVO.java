package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据库类型定义新增/修改 Request VO")
@Data
public class DatabaseTypeSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "24883")
    private Long id;

    @Schema(description = "数据源编码")
    private String typeCode;

    @Schema(description = "数据源名称", example = "王五")
    private String typeName;

    @Schema(description = "连接类型描述")
    @Size(max = 512, message = "连接类型描述不能超过512个字符")
    private String description;

    @Schema(description = "连接分类")
    private String categoryCode;

    @Schema(description = "连接协议类型", example = "2")
    private String protocolType;

    @Schema(description = "连接图标路径")
    private String iconPath;

    @Schema(description = "数据源URL")
    private String urlTemplates;

    @Schema(description = "默认端口")
    private String portTemplates;

    @Schema(description = "默认连接测试SQL")
    private String validationQuery;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注", example = "你猜")
    private String remark;

}
