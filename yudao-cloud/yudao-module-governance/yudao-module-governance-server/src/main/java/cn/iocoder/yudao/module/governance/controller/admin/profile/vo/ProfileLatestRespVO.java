package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据探查最新结果 Response VO")
@Data
public class ProfileLatestRespVO {

    @Schema(description = "最近一次探查（任意状态，用于轮询）；从未探查时为空")
    private ProfileRunRespVO latestRun;

    @Schema(description = "最近一次成功的探查；无成功记录时为空")
    private ProfileRunRespVO resultRun;

    @Schema(description = "resultRun 的字段指标")
    private List<ProfileColumnRespVO> columns;

    @Schema(description = "根据 resultRun 映射的规则建议")
    private List<ProfileRuleSuggestionRespVO> suggestions;

}
