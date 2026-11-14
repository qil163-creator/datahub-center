package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "管理后台 - 业务实体版本 Response VO")
@Data
public class BusinessEntityVersionRespVO {

    @Schema(description = "版本编号", example = "1")
    private Long id;

    @Schema(description = "业务实体编号", example = "1")
    private Long entityId;

    @Schema(description = "版本号，从 1 开始", example = "2")
    private Integer versionNo;

    @Schema(description = "展示用版本号", example = "V2")
    private String versionLabel;

    @Schema(description = "上线完成时实体的修订号", example = "6")
    private Long entityRevision;

    @Schema(description = "相对上一版本的变化摘要：initial、attributeChanged、relationAdded、relationRemoved")
    private Map<String, Object> changeSummary;

    @Schema(description = "上线操作人用户编号", example = "1")
    private Long publishedBy;

    @Schema(description = "上线操作人名称", example = "管理员")
    private String publishedByName;

    @Schema(description = "上线时间")
    private LocalDateTime publishedTime;
}
