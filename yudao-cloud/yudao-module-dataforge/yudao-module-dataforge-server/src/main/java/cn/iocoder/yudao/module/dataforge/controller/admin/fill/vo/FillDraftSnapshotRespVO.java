package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 在线填报-草稿快照 Response VO（进入工作台时全量拉取）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 草稿快照 Response VO")
@Data
public class FillDraftSnapshotRespVO {

    @Schema(description = "工作项编号", example = "1")
    private Long workItemId;

    @Schema(description = "任务名称", example = "民生项目月报（2026-08）")
    private String taskName;

    @Schema(description = "数据所属期", example = "2026-08")
    private String periodKey;

    @Schema(description = "组织名称", example = "市住建局")
    private String orgName;

    @Schema(description = "主状态", example = "1")
    private Integer status;

    @Schema(description = "逾期标识", example = "0")
    private Integer overdue;

    @Schema(description = "截止时间")
    private LocalDateTime deadlineTime;

    @Schema(description = "服务端版本（协同与提交的基准）", example = "135")
    private Long serverVersion;

    @Schema(description = "当前用户是否可编辑", example = "true")
    private Boolean editable;

    @Schema(description = "模板字段结构 JSON（模板版本固化的 fieldSchema）")
    private String fieldSchema;

    @Schema(description = "公式定义 JSON")
    private String formulaSchema;

    @Schema(description = "录入布局 JSON（冻结列等）")
    private String layoutSnapshot;

    @Schema(description = "校验规则 JSON")
    private String validationSchema;

    @Schema(description = "草稿行（按 rowOrder 升序）")
    private List<Row> rows;

    @Schema(description = "编号类字段的选项：人员/部门为可选范围，附件/图片为已引用附件的名称；key 为 fieldCode")
    private java.util.Map<String, List<RefOption>> refOptions;

    @Schema(description = "管理后台 - 编号类字段选项")
    @Data
    public static class RefOption {

        @Schema(description = "编号（单元格保存的值）", example = "12")
        private String value;

        @Schema(description = "显示名称", example = "张三")
        private String label;
    }

    @Schema(description = "管理后台 - 草稿行")
    @Data
    public static class Row {

        @Schema(description = "稳定行标识", example = "c-8f2a1b")
        private String rowId;

        @Schema(description = "显示排序")
        private BigDecimal rowOrder;

        @Schema(description = "行数据：fieldCode -> 值")
        private Map<String, Object> data;

        @Schema(description = "行版本", example = "3")
        private Long rowVersion;

        @Schema(description = "行创建人编号")
        private Long createUserId;

        @Schema(description = "行最后修改人编号")
        private Long lastModifyUserId;

        @Schema(description = "行来源：空-本单位录入 1-下级明细 2-下级分组合计（汇总行只读）")
        private Integer sourceKind;

        @Schema(description = "数据最初的报送组织编号")
        private Long sourceOrgId;

    }

}
