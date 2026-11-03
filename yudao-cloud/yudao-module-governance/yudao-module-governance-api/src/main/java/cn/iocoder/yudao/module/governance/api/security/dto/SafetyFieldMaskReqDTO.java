package cn.iocoder.yudao.module.governance.api.security.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

import lombok.Data;

/** 分类事实只能由持有对象读授权的提供方构造，不能直接转发前端自报类别。 */
@Data
public class SafetyFieldMaskReqDTO {

    @NotNull
    @Positive
    private Long userId;

    @NotBlank
    @Pattern(regexp = "QUERY")
    private String scene;

    @NotBlank
    @Pattern(regexp = "1|2")
    private String applicationScene = "1";

    @NotNull
    @Size(max = 1000)
    private List<@NotNull @Valid Field> fields;

    @NotNull
    @Size(max = 1000)
    private List<@NotNull Map<String, Object>> rows;

    @Data
    public static class Field {

        @NotBlank
        @Size(max = 512)
        private String fieldName;

        @NotNull
        @Size(min = 1, max = 100)
        private List<@NotNull @Positive Long> categoryIds;
    }
}
