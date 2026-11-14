package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 贴源表结构同步结果 Response VO")
@Data
public class SourceSyncApplyRespVO {

    @Schema(description = "同步后的字段是否已保存为当前设计", example = "true")
    private Boolean saved;

    @Schema(description = "是否已重新发布并生成新版本；原本为草稿的模型保持草稿", example = "true")
    private Boolean published;

    @Schema(description = "重新发布失败的原因；字段已保存为草稿，可修正后再发布", example = "主题域已停用")
    private String publishMessage;
}
