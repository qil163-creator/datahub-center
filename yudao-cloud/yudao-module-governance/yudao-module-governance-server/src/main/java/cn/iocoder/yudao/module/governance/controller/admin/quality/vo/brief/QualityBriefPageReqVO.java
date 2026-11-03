package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 质检简报分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityBriefPageReqVO extends PageParam {

    @Schema(description = "简报标题（模糊）")
    private String briefTitle;

    @Schema(description = "简报状态：DRAFT/PUBLISHED")
    private String briefStatus;

    @Schema(description = "模板编号")
    private Long templateId;

}
