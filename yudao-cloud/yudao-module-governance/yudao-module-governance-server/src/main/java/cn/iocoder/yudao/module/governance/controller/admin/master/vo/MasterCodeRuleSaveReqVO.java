package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterCodeRuleSaveReqVO")
@Data
public class MasterCodeRuleSaveReqVO {

    private Long id;

    @NotNull
    private Long modelId;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String fieldName;

    @Size(max = 500)
    private String description;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;

    @NotEmpty
    @Size(max = 12)
    @Valid
    private List<MasterCodeSegmentVO> segments;
}
