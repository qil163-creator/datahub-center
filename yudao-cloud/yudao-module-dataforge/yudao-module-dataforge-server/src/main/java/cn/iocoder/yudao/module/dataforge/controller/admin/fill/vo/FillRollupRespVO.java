package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 在线填报-汇总下级数据 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 汇总下级数据 Response VO")
@Data
public class FillRollupRespVO {

    @Schema(description = "纳入汇总的下级数", example = "3")
    private Integer childCount;

    @Schema(description = "汇总生成的行数", example = "36")
    private Integer rollupRowCount;

    @Schema(description = "汇总后草稿总行数", example = "40")
    private Integer draftRowCount;

}
