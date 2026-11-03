package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据同步表级结果 Response VO")
@Data
public class DataAccessTaskDetailRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据同步任务id", example = "1024")
    private Long dataAccessId;

    @Schema(description = "执行记录id", example = "1")
    private Long taskId;

    @Schema(description = "源端 schema", example = "public")
    private String sourceSchemaName;

    @Schema(description = "源端表名", example = "t_user")
    private String sourceTableName;

    @Schema(description = "目的端 schema", example = "public")
    private String targetSchemaName;

    @Schema(description = "目的端表名", example = "ods_t_user")
    private String targetTableName;

    @Schema(description = "同步记录数", example = "10000")
    private Long syncCount;

    @Schema(description = "同步数据量", example = "10.5MB")
    private String syncBytes;

    @Schema(description = "是否成功：0-否 1-是", example = "1")
    private Integer ifSuccess;

    @Schema(description = "失败信息")
    private String errorMsg;

    @Schema(description = "成功信息")
    private String successMsg;

    @Schema(description = "本轮类型映射决策，包括失败前的拒绝原因；历史或原生同产品路径可为空")
    private AccessTypeMappingPlanRespVO typeMappingPlan;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
