package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据需求保存")
@Data
public class MarketDemandSaveReqVO {

    private Long id;

    @NotBlank
    @Size(max = 128)
    private String title;

    @NotNull
    private Integer demandType;

    @NotBlank
    @Size(max = 2000)
    private String description;

    @Size(max = 500)
    private String purpose;

    @Size(max = 1000)
    private String expectedFields;

    @Size(max = 64)
    private String expectedUpdateFreq;

    private Integer expectedDelivery;

    private LocalDateTime expectFinishTime;

    private Integer urgency;

    private Long applyDeptId;

    @Size(max = 32)
    private String phone;

    @Size(max = 64)
    private String email;

    @Schema(description = "是否直接发布到需求大厅")
    private Boolean publish;

    @Size(max = 500)
    private String remark;

}
