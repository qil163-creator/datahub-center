package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 简报模板分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityBriefTemplatePageReqVO extends PageParam {

    @Schema(description = "模板名称（模糊）")
    private String templateName;

    @Schema(description = "评估周期：DAY/WEEK/MONTH")
    private String assessCycle;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

}
