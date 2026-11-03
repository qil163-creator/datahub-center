package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 在线填报-数据更正分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数据更正分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillCorrectionPageReqVO extends PageParam {

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "状态：0-草拟 1-审批中 2-已合并 3-已取消", example = "0")
    private Integer status;

}
