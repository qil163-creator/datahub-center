package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据集市申请保存")
@Data
public class MarketApplySaveReqVO {

    private Long id;

    @NotBlank(message = "申请标题不能为空")
    @Size(max = 128)
    private String title;

    @NotNull(message = "资产不能为空")
    private Long assetId;

    private Long apiId;

    private Long appId;

    private Long demandId;

    private Long applyDeptId;

    @Size(max = 32)
    private String phone;

    @Size(max = 64)
    private String email;

    @Size(max = 500)
    private String purpose;

    @Size(max = 256)
    private String dutyMatter;

    @Size(max = 500)
    private String legalBasis;

    @Size(max = 1000)
    private String fieldScope;

    @Size(max = 500)
    private String applyNote;

    private LocalDateTime applyStartTime;

    private LocalDateTime applyEndTime;

    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(2)
    private Integer applyUseType;

    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(Integer.MAX_VALUE)
    private Integer applyUseTimes;

    @Schema(description = "是否直接提交待审，默认 true")
    private Boolean submit;

    @Schema(description = "编辑时客户端所见的申请内容版本；提供时必须与当前一致，缺省保持服务端版本 CAS")
    private Integer expectedRevision;

    @Size(max = 500)
    private String remark;

}
