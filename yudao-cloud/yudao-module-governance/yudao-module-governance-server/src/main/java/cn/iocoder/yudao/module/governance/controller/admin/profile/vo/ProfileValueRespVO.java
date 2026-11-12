package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据探查 Top 值 Response VO")
@Data
public class ProfileValueRespVO {

    @Schema(description = "排名，从 1 开始")
    private Integer rankNo;

    @Schema(description = "取值文本；已脱敏时为空")
    private String value;

    @Schema(description = "抽样内出现次数")
    private Long count;

    @Schema(description = "占非空抽样的比例，十进制字符串（0~1）")
    private String ratio;

    @Schema(description = "是否已脱敏（敏感字段只返回计数）")
    private Boolean masked;

}
