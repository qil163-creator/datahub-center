package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 库级批量发布结果 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 库级批量发布结果 Response VO")
@Data
public class MetaPublishBatchRespVO {

    @Schema(description = "正常状态表总数", example = "20")
    private Integer total;

    @Schema(description = "本次发布成功数", example = "15")
    private Integer published;

    @Schema(description = "此前已发布数（不重复定版）", example = "3")
    private Integer alreadyPublished;

    @Schema(description = "校验未通过跳过数", example = "2")
    private Integer skipped;

    @Schema(description = "跳过原因（最多 20 条）", example = "[\"t_user：业务负责人未认责\"]")
    private List<String> skippedReasons = new ArrayList<>();

}
