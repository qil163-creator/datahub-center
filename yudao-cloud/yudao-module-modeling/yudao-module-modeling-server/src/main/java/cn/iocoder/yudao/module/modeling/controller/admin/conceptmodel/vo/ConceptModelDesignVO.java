package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "概念模型实体、属性与关系定义")
public class ConceptModelDesignVO {

    @NotNull
    @Size(max = 50)
    @Valid
    private List<@NotNull Entity> entities = new ArrayList<>();

    @NotNull
    @Size(max = 100)
    @Valid
    private List<@NotNull Relation> relations = new ArrayList<>();

    @Data
    public static class Entity {

        @Schema(description = "规划实体类型：业务对象或业务活动")
        @Pattern(regexp = "OBJECT|ACTIVITY")
        private String entityType;

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private String key;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$")
        private String code;

        @Size(max = 500)
        private String description;

        @NotNull
        @Size(max = 30)
        @Valid
        private List<@NotNull Attribute> attributes = new ArrayList<>();

        @Min(-100000)
        @Max(100000)
        private Integer posX;

        @Min(-100000)
        @Max(100000)
        private Integer posY;
    }

    @Data
    public static class Attribute {

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private String key;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$")
        private String code;

        @Size(max = 500)
        private String description;

        @Pattern(regexp = "STRING|NUMBER|BOOLEAN|DATE|DATETIME")
        private String dataType;

        private Boolean primary;
    }

    @Data
    public static class Relation {

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private String key;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Size(max = 64)
        private String sourceEntityKey;

        @NotBlank
        @Size(max = 64)
        private String targetEntityKey;

        @NotBlank
        @Pattern(regexp = "ONE_TO_ONE|ONE_TO_MANY|MANY_TO_MANY")
        private String cardinality;

        @Size(max = 500)
        private String description;
    }
}
