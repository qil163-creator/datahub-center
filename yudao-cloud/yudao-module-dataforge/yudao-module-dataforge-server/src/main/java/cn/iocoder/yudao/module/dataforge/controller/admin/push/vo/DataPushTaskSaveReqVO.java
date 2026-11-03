package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据推送任务保存 Request VO")
@Data
public class DataPushTaskSaveReqVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "既有推送配置编号；与内嵌配置二选一，兼容原有调用")
    private Long pushConfigId;

    @Schema(description = "任务接收端完整配置；新建时不能携带配置编号，编辑时仅允许本任务原配置编号")
    @Valid
    private DataPushConfigSaveReqVO pushConfig;

    /**
     * 判断是否满足推送配置选择有效条件。
     *
     * @return 判断结果
     */
    @AssertTrue(message = "既有推送配置编号与任务接收端配置必须且只能填写一个")
    @Schema(hidden = true)
    public boolean isPushConfigSelectionValid() {
        return (pushConfigId != null) != (pushConfig != null);
    }

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称不能超过 128 个字符")
    private String name;

    @Schema(description = "推送类目编号")
    @Positive
    private Long categoryId;

    @Schema(description = "源数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "源数据源不能为空")
    private Long sourceDatabaseId;

    @Schema(description = "推送类型：1-数据表 2-自定义 SQL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "推送类型不能为空")
    private Integer pushType;

    @Schema(description = "源表名")
    private String tableName;

    @Schema(description = "自定义 SELECT")
    private String sqlText;

    @Schema(description = "接收方逻辑对象名")
    @Size(max = 256, message = "对象名不能超过 256 个字符")
    private String objectName;

    @Schema(description = "推送方式：1-全量 2-增量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "推送方式不能为空")
    private Integer pushMode;

    @Schema(description = "增量字段")
    private String incrementColumn;

    @Schema(description = "首次增量起始水位（不含）；空则从最小扫到当前 MAX")
    @Size(max = 256, message = "首次起始水位不能超过 256 个字符")
    private String initStartVal;

    @Schema(description = "每批行数", example = "100")
    private Integer batchSize;

    @Schema(description = "调度类型：1-一次性 2-周期性", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "调度类型不能为空")
    private Integer taskType;

    @Schema(description = "Cron 表达式")
    private String cron;

    @Schema(description = "说明")
    @Size(max = 500, message = "说明不能超过 500 个字符")
    private String description;

    @Schema(description = "所属机构编号")
    private Long orgId;

}
