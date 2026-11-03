package cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "管理后台 - 来源系统精简 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceSystemSimpleRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "4")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水利数据仓库-DWS汇总层")
    private String name;

}
