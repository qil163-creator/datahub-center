package cn.iocoder.yudao.module.governance.controller.admin.standardversion.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 标准对象版本快照 Response VO")
@Data
public class StandardObjectVersionRespVO {

    @Schema(description = "版本编号", example = "1")
    private Long id;

    @Schema(description = "对象类型：DOC-标准文件 CODE_SET-码表", example = "CODE_SET")
    private String objectType;

    @Schema(description = "对象编号", example = "1024")
    private Long objectId;

    @Schema(description = "版本号", example = "2")
    private Integer version;

    @Schema(description = "版本快照 JSON")
    private String snapshot;

    @Schema(description = "变更说明", example = "补充证件类型码值")
    private String changeSummary;

    @Schema(description = "创建人")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
