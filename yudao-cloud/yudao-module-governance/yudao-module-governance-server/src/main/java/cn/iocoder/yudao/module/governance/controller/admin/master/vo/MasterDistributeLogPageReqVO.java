package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 主数据派发执行记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDistributeLogPageReqVO extends PageParam {

    @Schema(description = "派发任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "派发任务编号不能为空")
    private Long distributeId;

    @Schema(description = "运行状态：1-等待派发 2-运行中 3-成功 4-失败 5-结果未知 6-已人工处置（非成功）", example = "3")
    private Integer runStatus;

}
