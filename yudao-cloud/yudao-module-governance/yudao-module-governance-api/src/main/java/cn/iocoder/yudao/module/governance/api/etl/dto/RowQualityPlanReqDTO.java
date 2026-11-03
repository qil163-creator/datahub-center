package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RowQualityPlanReqDTO {

    @NotNull
    private Long executionUserId;

    private Long ruleSetId;

    @Size(max = 100)
    private List<@NotNull Long> ruleIds;

    /** 十进制规则编号字符串到 ETL 输入字段路径的映射；模式值仅供参考，绝不作为授权凭据。 */
    @Size(max = 100)
    private Map<@NotNull @Size(max = 20) String, @NotNull @Size(max = 256) String> fieldBindings;

    @Size(max = 500)
    private List<@NotNull @Size(max = 256) String> inputFields;
}
