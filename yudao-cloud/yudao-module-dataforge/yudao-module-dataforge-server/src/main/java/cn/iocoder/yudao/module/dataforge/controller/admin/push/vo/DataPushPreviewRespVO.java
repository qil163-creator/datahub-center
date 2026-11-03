package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据推送试跑预览 Response VO")
@Data
public class DataPushPreviewRespVO {

    @Schema(description = "通道类型")
    private Integer channelType;

    @Schema(description = "接收方对象名")
    private String objectName;

    @Schema(description = "列名")
    private List<String> columns;

    @Schema(description = "预览行")
    private List<Map<String, Object>> rows;

    @Schema(description = "实际预览行数")
    private Integer rowCount;

    @Schema(description = "增量下界（不含）")
    private String incrementStart;

    @Schema(description = "增量上界（含）")
    private String incrementEnd;

    @Schema(description = "是否空区间，无需推送")
    private Boolean emptyInterval;

    @Schema(description = "即将 POST 的 payload 摘要（截断）")
    private String payloadPreview;

    @Schema(description = "说明")
    private String hint;

}
