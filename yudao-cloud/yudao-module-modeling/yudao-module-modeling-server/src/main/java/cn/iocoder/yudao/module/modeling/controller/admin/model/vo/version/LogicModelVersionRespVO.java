package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "管理后台 - 逻辑模型版本 Response VO")
@Data
public class LogicModelVersionRespVO {

    @Schema(description = "版本编号", example = "1")
    private Long id;

    @Schema(description = "逻辑模型编号", example = "1")
    private Long modelId;

    @Schema(description = "版本号，从 1 开始", example = "3")
    private Integer versionNo;

    @Schema(description = "展示用版本号", example = "V3")
    private String versionLabel;

    @Schema(description = "发布完成时模型的修订号", example = "12")
    private Long modelRevision;

    @Schema(description = "快照字段数", example = "18")
    private Integer fieldCount;

    @Schema(description = "相对上一版本的变化摘要：initial、added、removed、modified、headerChanged")
    private Map<String, Object> changeSummary;

    @Schema(description = "发布人用户编号", example = "1")
    private Long publishedBy;

    @Schema(description = "发布人名称", example = "管理员")
    private String publishedByName;

    @Schema(description = "发布时间")
    private LocalDateTime publishedTime;
}
