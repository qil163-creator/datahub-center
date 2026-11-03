package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数仓分层列表 Request VO")
@Data
public class DwLayerListReqVO {

    @Schema(description = "分层名称", example = "芋艿")
    private String layerName;

    @Schema(description = "是否内置：0否 1是，内置节点不允许删除")
    private Short isBuiltin;

    @Schema(description = "状态：0停用 1启用", example = "2")
    private Short status;

}