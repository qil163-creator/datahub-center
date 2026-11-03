package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 主数据模型按需数据量 Response VO")
@Data
public class MasterModelDataCountRespVO {

    @Schema(description = "物理表记录数；查询不可用时为 null，不能当作零")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Long dataCount;

    @Schema(description = "是否已取得有效记录数", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean available;

    @Schema(description = "查询不可用时的安全提示")
    private String message;
}
