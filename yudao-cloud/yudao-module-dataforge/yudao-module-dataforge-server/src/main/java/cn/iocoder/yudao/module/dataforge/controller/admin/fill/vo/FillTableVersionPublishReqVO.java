package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-模板版本发布 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 模板版本发布 Request VO")
@Data
public class FillTableVersionPublishReqVO {

    @Schema(description = "填报表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "填报表编号不能为空")
    private Long tableId;

    @Schema(description = "合并策略：APPEND/UPSERT/REPLACE_SCOPE", requiredMode = Schema.RequiredMode.REQUIRED, example = "UPSERT")
    @NotEmpty(message = "合并策略不能为空")
    private String mergeStrategy;

    @Schema(description = "UPSERT 业务键 fieldCode 列表（UPSERT 策略必填）", example = "[\"org_code\",\"period\"]")
    private List<String> businessKeyFields;

}
