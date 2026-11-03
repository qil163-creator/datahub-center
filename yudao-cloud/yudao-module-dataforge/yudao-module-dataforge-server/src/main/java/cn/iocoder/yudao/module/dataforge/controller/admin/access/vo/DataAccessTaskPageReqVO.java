package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据同步执行记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAccessTaskPageReqVO extends PageParam {

    @Schema(description = "数据同步任务id", example = "1024")
    private Long dataAccessId;

    @Schema(description = "任务名称", example = "人口库同步")
    private String taskName;

    @Schema(description = "运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束", example = "3")
    private Integer runStatus;

    @Schema(description = "触发方式：1-自动调度 2-手动执行", example = "2")
    private Integer runType;

}
