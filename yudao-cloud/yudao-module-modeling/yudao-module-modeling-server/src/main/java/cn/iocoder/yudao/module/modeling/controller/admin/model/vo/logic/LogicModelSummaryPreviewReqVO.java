package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "汇总逻辑表自动字段预览")
public class LogicModelSummaryPreviewReqVO {

    @NotNull
    private Long dataDomainId;

    @NotNull
    private Long subjectDomainId;

    @Pattern(regexp = "1|2|3")
    private String dataTimeliness;

    @Size(max = 32)
    private List<Long> grainEntityIds;
}
