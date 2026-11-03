package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.Data;
import jakarta.validation.constraints.*;

import java.util.Map;

@Data
public class StandardElementRuleSampleReqVO {

    @NotNull
    private Integer elementId;

    @NotNull
    private Long templateId;

    @NotNull
    private Map<String, Object> config;

    @NotNull
    @Size(max = 4096)
    private String sample;
}
