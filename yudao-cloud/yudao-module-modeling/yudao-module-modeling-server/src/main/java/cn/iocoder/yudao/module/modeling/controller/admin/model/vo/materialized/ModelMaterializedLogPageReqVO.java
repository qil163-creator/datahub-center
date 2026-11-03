package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 物化发布日志分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ModelMaterializedLogPageReqVO extends PageParam {

    @Schema(description = "物化记录编号")
    private Long materializedId;

    @Schema(description = "逻辑模型编号")
    private Long modelId;

    @Schema(description = "状态")
    private String status;
}
