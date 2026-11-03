package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 修复工单 Response VO")
@Data
public class QualityRepairOrderRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "工单标题")
    private String orderTitle;

    @Schema(description = "修复类型")
    private String repairType;

    @Schema(description = "检查对象编号")
    private Long objectId;

    @Schema(description = "检测表")
    private String tableName;

    @Schema(description = "检测字段")
    private String checkField;

    @Schema(description = "目标字段")
    private String targetField;

    @Schema(description = "修复配置JSON")
    private String repairConfig;

    @Schema(description = "修复SQL")
    private String repairSql;

    @Schema(description = "审批状态")
    private String approveStatus;

    @Schema(description = "执行状态")
    private String execStatus;

    @Schema(description = "验证状态")
    private String verifyStatus;

    @Schema(description = "修复说明")
    private String repairDesc;

    @Schema(description = "执行结果")
    private String execResult;

    @Schema(description = "执行时间")
    private LocalDateTime execTime;

    @Schema(description = "审批内容摘要")
    private String approvedContentHash;

    private String approvedSnapshot;

    @Schema(description = "内容版本")
    private Integer contentVersion;

    @Schema(description = "预期影响行数上限")
    private Integer maxAffectedRows;

    @Schema(description = "创建者")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
