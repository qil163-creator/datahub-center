package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SafetyCategoryBatchReqVO {

    @Valid
    @NotEmpty
    @Size(max = 100)
    private List<SafetyCategoryVersionReqVO> items;
}
