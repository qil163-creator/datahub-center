package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据推送任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataPushTaskPageReqVO extends PageParam {

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "推送类目编号")
    @Positive
    private Long categoryId;

    @Schema(description = "推送类型")
    private Integer pushType;

    @Schema(description = "推送方式")
    private Integer pushMode;

    @Schema(description = "调度类型")
    private Integer taskType;

    @Schema(description = "发布状态")
    private Integer status;

    @Schema(description = "源数据源编号")
    private Long sourceDatabaseId;

    @Schema(description = "所属机构编号")
    private Long orgId;

}
