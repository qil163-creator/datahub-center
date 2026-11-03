package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 未保存的评测可在当前用户权限下抽查，样例仅作纯值求值。 */
@Data
public class QualityTaskProbeReqVO {

    @Valid
    @NotNull
    private QualityTaskEvaluationSaveReqVO evaluation;

    @Valid
    private QualityTaskObjectSaveReqVO object;

    @Valid
    @Size(max = 100)
    private List<QualityTaskObjectSaveReqVO> objects;

    @Size(max = 4096)
    private String sampleData;

    @Min(1)
    @Max(100)
    private Integer limit;
}
