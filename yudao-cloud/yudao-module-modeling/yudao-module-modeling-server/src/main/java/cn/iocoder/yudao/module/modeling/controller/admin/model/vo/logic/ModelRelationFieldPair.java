package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ModelRelationFieldPair {

    @NotBlank
    private String sourceFieldUid;

    @NotBlank
    private String targetFieldUid;
}
