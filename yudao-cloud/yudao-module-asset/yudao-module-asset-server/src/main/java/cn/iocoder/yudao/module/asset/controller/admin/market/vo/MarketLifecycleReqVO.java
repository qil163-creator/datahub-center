package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 申请补正/续期/暂停")
@Data
public class MarketLifecycleReqVO {

    @NotNull
    private Long id;

    @Size(max = 500)
    private String note;

    private LocalDateTime applyEndTime;

    @Schema(description = "要求补正时所见的申请内容版本；提供时必须一致")
    private Integer expectedRevision;

    @Schema(description = "暂停时所见的交付来源版本；提供时必须一致")
    private Long expectedSourceRevision;
}
