package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.module.dataforge.service.access.AccessTypeMappingPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 跨产品类型映射预览与执行诊断")
@Data
public class AccessTypeMappingPlanRespVO {

    private int schemaVersion = 1;

    private String sourceProduct;

    private String sourceSchema;

    private String sourceTable;

    private String targetProduct;

    private String targetSchema;

    private String targetTable;

    private String fidelity;

    @Schema(description = "预览默认拒绝全部有损列；执行诊断表示本轮重算是否接受")
    private boolean accepted;

    @Schema(description = "原始计划的总列数")
    private int totalColumns;

    @Schema(description = "诊断为有界摘要，不能视为全部列和原因都已展示")
    private boolean truncated;

    private List<AccessTypeMappingPlan.Column> columns = List.of();
}
