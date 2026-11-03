package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 机构评分分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityOrgScorePageReqVO extends PageParam {

    @Schema(description = "规则集编号")
    private Long ruleSetId;

    @Schema(description = "机构名称（模糊）")
    private String orgName;

    @Schema(description = "周期开始（YYYYMM）")
    private String periodStart;

    @Schema(description = "周期结束（YYYYMM）")
    private String periodEnd;

}
