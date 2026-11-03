package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 数据推送执行记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataPushTaskLogPageReqVO extends PageParam {

    @Schema(description = "推送任务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "推送任务不能为空")
    private Long pushTaskId;

    @Schema(description = "运行状态：1-等待 2-运行中 3-成功 4-失败")
    private Integer runStatus;

}
