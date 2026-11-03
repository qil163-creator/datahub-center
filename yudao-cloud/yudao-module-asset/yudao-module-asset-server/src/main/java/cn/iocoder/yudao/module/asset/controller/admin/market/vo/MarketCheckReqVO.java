package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据集市审批")
@Data
public class MarketCheckReqVO {

    @NotNull(message = "申请编号不能为空")
    private Long id;

    @NotNull(message = "审批结果不能为空")
    @Schema(description = "2-通过 3-驳回")
    private Integer status;

    @Size(max = 500)
    private String checkNote;

    @Schema(description = "审批人所见的申请内容版本；提供时必须与当前一致，防止审批未看过的补正内容")
    private Integer expectedRevision;

}
