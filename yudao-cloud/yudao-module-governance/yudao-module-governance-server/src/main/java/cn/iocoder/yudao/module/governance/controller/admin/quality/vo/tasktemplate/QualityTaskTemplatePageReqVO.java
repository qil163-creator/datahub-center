package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 任务模板分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskTemplatePageReqVO extends PageParam {

    @Schema(description = "模板名称（模糊）")
    private String templateName;

    @Schema(description = "数据源连接ID")
    private Long datasourceConnId;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

}
