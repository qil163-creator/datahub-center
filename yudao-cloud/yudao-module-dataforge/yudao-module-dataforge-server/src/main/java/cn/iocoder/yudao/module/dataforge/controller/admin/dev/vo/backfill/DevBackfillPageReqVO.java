package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 数据开发补数据批次分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DevBackfillPageReqVO extends PageParam {

    @Schema(description = "开发任务编号", example = "1024")
    private Long taskId;

    @Schema(description = "状态：RUNNING/SUCCESS/FAILED/CANCELLED", example = "RUNNING")
    private String status;

}
