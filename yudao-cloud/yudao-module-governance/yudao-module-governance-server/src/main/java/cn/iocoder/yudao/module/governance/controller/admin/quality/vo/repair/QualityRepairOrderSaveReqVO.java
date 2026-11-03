package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 修复工单新增/修改 Request VO")
@Data
public class QualityRepairOrderSaveReqVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "工单标题")
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 256, message = "工单标题长度不能超过 256")
    private String orderTitle;

    @Schema(description = "修复类型：FILL_DEFAULT/DELETE_RECORD/FORMAT_FIX/DEDUP/STANDARD_MAP/CUSTOM_SQL")
    @NotBlank(message = "修复类型不能为空")
    private String repairType;

    @Schema(description = "检查对象编号")
    private Long objectId;

    @Schema(description = "检测表")
    @Size(max = 128)
    private String tableName;

    @Schema(description = "检测字段")
    @Size(max = 128)
    private String checkField;

    @Schema(description = "目标字段")
    @Size(max = 128)
    private String targetField;

    @Schema(description = "修复配置JSON（各修复类型的参数）")
    private String repairConfig;

    @Schema(description = "修复SQL（为空时按修复配置自动生成）")
    private String repairSql;

    @Schema(description = "修复说明")
    @Size(max = 1024)
    private String repairDesc;

    @Schema(description = "预期影响行数上限，超过则在已验证事务语义内回滚")
    private Integer maxAffectedRows;

}
