package cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "管理后台 - 逻辑表批量删除检查 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogicTableDeleteCheckRespVO {

    @Schema(description = "可删除数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer canDeleteCount;

    @Schema(description = "不可删除数量（已启用）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer cannotDeleteCount;

    @Schema(description = "可删除的编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> canDeleteIds;

}
