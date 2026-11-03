package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Schema(description = "管理后台 - 数据推送试跑预览 Request VO")
@Data
public class DataPushPreviewReqVO {

    @Schema(description = "已保存任务编号；仅编号则预览已保存任务，携带草稿配置则预览编辑内容")
    private Long id;

    @Schema(description = "预览行数", example = "10")
    @Min(value = 1, message = "预览行数至少 1")
    @Max(value = 50, message = "预览行数不能超过 50")
    private Integer limit;

    @Schema(description = "既有推送配置编号；草稿预览时与内嵌配置二选一")
    private Long pushConfigId;

    @Schema(description = "草稿接收端完整配置；配置编号仅可对应本次编辑任务的原配置")
    @Valid
    private DataPushConfigSaveReqVO pushConfig;

    @Schema(description = "源数据源编号")
    private Long sourceDatabaseId;

    @Schema(description = "推送类型：1-表 2-SQL")
    private Integer pushType;

    @Schema(description = "源表名")
    private String tableName;

    @Schema(description = "自定义 SELECT")
    private String sqlText;

    @Schema(description = "对象名")
    private String objectName;

    @Schema(description = "推送方式：1-全量 2-增量")
    private Integer pushMode;

    @Schema(description = "增量字段")
    private String incrementColumn;

    @Schema(description = "首次起始水位")
    private String initStartVal;

    @Schema(description = "每批行数")
    private Integer batchSize;

    @Schema(description = "调度类型")
    private Integer taskType;

    @Schema(description = "Cron")
    private String cron;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "所属机构")
    private Long orgId;

}
