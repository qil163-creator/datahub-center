package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 在线填报-合并批次分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 合并批次分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillMergeBatchPageReqVO extends PageParam {

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "工作项编号", example = "1")
    private Long workItemId;

    @Schema(description = "状态：0-待执行 1-执行中 2-成功 3-失败", example = "3")
    private Integer status;

    @Schema(description = "目标物理表名", example = "ms_project_monthly")
    private String targetTableName;

}
