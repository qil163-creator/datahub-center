package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据同步任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAccessPageReqVO extends PageParam {

    @Schema(description = "任务名称", example = "人口库同步")
    private String taskName;

    @Schema(description = "同步引擎类型：1-离线同步 2-实时同步 3-API接入", example = "1")
    private Integer engineType;

    @Schema(description = "同步引擎类型列表（多选过滤，与 engineType 二选一）", example = "[1,2]")
    private java.util.List<Integer> engineTypes;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

    @Schema(description = "源端数据源id", example = "1")
    private Long sourceDatabaseId;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer status;

    @Schema(description = "运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束", example = "3")
    private Integer runStatus;

}
