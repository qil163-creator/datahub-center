package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 动态脱敏场景 Response VO
 */
@Schema(description = "管理后台 - 动态脱敏场景 Response VO")
@Data
public class SafetyMaskSceneRespVO {

    @Schema(description = "编号；尚未配置时为空", example = "1")
    private Long id;

    @Schema(description = "场景编码", example = "DATA_QUERY")
    private String sceneCode;

    @Schema(description = "场景名称", example = "数据查询")
    private String sceneName;

    @Schema(description = "对应的白名单豁免场景", example = "QUERY")
    private String whitelistScene;

    @Schema(description = "是否启用", example = "true")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "修订号", example = "0")
    private Integer revision;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
