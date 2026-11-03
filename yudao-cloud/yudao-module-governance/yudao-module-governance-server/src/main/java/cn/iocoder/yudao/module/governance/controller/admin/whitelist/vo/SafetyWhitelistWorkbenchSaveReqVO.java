package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

import lombok.Data;

@Data
public class SafetyWhitelistWorkbenchSaveReqVO {

    @Positive
    private Long id;

    @Positive
    private Long revision;

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[\\p{IsHan}a-zA-Z0-9_]+")
    private String name;

    @NotNull
    @Positive
    private Long categoryId;

    @NotBlank
    @Pattern(regexp = "1")
    private String effectiveCategory;

    @NotNull
    @Size(min = 1, max = 100)
    private List<@NotNull @Positive Long> userIds;

    @NotNull
    private LocalDate startTime;

    @NotNull
    private LocalDate endTime;

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;

    @Size(max = 256)
    private String description;
}
