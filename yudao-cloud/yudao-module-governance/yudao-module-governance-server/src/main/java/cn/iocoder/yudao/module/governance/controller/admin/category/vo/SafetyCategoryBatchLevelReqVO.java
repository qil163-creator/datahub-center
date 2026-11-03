package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyCategoryBatchLevelReqVO extends SafetyCategoryBatchReqVO {

    @NotNull
    @Positive
    private Integer dataLevelId;
}
