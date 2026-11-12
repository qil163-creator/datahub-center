package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageDirectionEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 表级血缘图 Request VO")
@Data
public class LineageGraphReqVO {

    @Schema(description = "根节点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "节点编号不能为空")
    private Long nodeId;

    @Schema(description = "方向：UPSTREAM/DOWNSTREAM/BOTH，默认 BOTH")
    @InEnum(value = LineageDirectionEnum.class, message = "方向必须是 {value}")
    private String direction = LineageDirectionEnum.BOTH.name();

    @Schema(description = "展开层数 1-5，默认 2")
    @Min(value = 1, message = "层数不能小于 1")
    @Max(value = 5, message = "层数不能超过 5")
    private Integer depth = 2;

}
