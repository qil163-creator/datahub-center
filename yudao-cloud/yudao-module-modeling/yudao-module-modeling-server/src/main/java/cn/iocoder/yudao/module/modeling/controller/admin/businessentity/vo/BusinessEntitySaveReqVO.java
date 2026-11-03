package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "业务实体保存请求")
public class BusinessEntitySaveReqVO {

    @NotNull
    @Positive
    private Long dataDomainId;

    @NotNull
    @Positive
    private Long subjectDomainId;

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*")
    private String code;

    @NotBlank
    @Pattern(regexp = "OBJECT|ACTIVITY")
    private String entityType;

    @NotBlank
    @Pattern(regexp = "NORMAL|HIERARCHICAL|EVENT|PROCESS")
    private String entitySubtype;

    @Positive
    private Long leaderUserId;

    @Size(max = 256)
    private String description;

    @Positive
    private Long inheritEntityId;

    @Size(max = 100)
    private List<@NotNull @Positive Long> associatedEntityIds = new ArrayList<>();

    @Size(max = 100)
    private List<@NotNull @Positive Long> predecessorEntityIds = new ArrayList<>();

    @Size(max = 100)
    private List<@NotNull @Positive Long> processEventIds = new ArrayList<>();
}
