package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 元数据采集任务创建/更新 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集任务创建/更新 Request VO")
@Data
public class MetaCollectTaskSaveReqVO {

    @NotNull
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(2)
    private Integer collectType = 1;

    @NotNull
    @jakarta.validation.constraints.Pattern(regexp = "PARALLEL|SERIAL_WAIT|SERIAL_DISCARD|SERIAL_PRIORITY")
    private String executeStrategy = "SERIAL_DISCARD";

    @Size(max = 4096)
    private String filePath;


    @Schema(description = "任务编号（更新时必填）", example = "1")
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "数据中台应用库（ADS）")
    @jakarta.validation.constraints.NotBlank(message = "任务名称不能为空")
    @Size(max = 200, message = "任务名称长度不能超过 200")
    private String name;

    @Schema(description = "dataforge 数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    @Schema(description = "来源系统编号", example = "1")
    private Long sourceSystemId;

    @Schema(description = "来源系统名称（展示快照）", example = "山洪灾害监测预警系统")
    private String sourceSystemName;

    @Schema(description = "采集范围：1-整个数据源 2-自定义", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "采集范围不能为空")
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(2)
    private Integer scopeType;

    @Schema(description = "自定义范围 JSON：[{\"db\":\"dwd\",\"tables\":[]}]")
    @Size(max = 65536)
    private String scopeConfig;

    @Schema(description = "黑名单规则 JSON：[\"tmp_*\",\"bak_*\"]")
    @Size(max = 16384)
    private String blacklist;

    @Schema(description = "调度周期 cron 表达式", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "责任人用户编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "责任人电话", example = "13800000000")
    @Size(max = 32)
    private String ownerPhone;

    @Schema(description = "责任部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "描述")
    @Size(max = 256)
    private String description;

    @Schema(description = "备注")
    @Size(max = 256)
    private String remark;

}
