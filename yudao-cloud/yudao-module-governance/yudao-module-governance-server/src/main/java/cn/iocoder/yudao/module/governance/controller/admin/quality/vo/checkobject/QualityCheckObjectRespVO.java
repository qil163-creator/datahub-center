package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 检查对象 Response VO")
@Data
public class QualityCheckObjectRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "检查对象名称")
    private String objectName;

    @Schema(description = "数据源连接ID")
    private Long datasourceConnId;

    @Schema(description = "数据源连接名称")
    private String datasourceConnName;

    @Schema(description = "数据连接类型")
    private String datasourceType;

    @Schema(description = "数据连接实例")
    private String datasourceInstance;

    @Schema(description = "格式名称")
    private String formatName;

    @Schema(description = "表名称")
    private String tableName;

    @Schema(description = "关联元数据表ID")
    private Long tableId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
