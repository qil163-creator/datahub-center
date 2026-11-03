package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 当前授权范围的问题行查询")
public class QualityIssueRowQueryReqVO extends PageParam {

    @NotNull
    @Positive
    private Long auditLogId;

    @Size(max = 100)
    private Map<@NotBlank @Size(max = 256) String, @Size(max = 256) String> filters;
}
