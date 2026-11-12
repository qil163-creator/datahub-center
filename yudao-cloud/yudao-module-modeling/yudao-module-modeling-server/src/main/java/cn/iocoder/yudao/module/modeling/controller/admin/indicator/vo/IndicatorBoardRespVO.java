package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台 - 指标看板 Response VO
 */
@Schema(description = "管理后台 - 指标看板 Response VO")
@Data
public class IndicatorBoardRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "名称", example = "经营日报")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "创建人编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "当前用户是否可修改")
    private Boolean editable;

    @Schema(description = "组件（列表接口不返回）")
    private List<IndicatorBoardSaveReqVO.Widget> widgets;

    @Schema(description = "组件数量", example = "4")
    private Integer widgetCount;

    @Schema(description = "修订号", example = "0")
    private Integer revision;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
