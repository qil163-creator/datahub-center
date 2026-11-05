package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 在线填报-提交快照预览 Response VO（审批人/历史查看者的只读视图）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 提交快照预览 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillSubmissionPreviewRespVO extends FillSubmissionRespVO {

    @Schema(description = "任务名称", example = "民生项目月报（2026-08）")
    private String taskName;

    @Schema(description = "数据所属期", example = "2026-08")
    private String periodKey;

    @Schema(description = "报送组织名称", example = "市住建局")
    private String orgName;

    @Schema(description = "模板字段结构 JSON（渲染只读表格用）")
    private String fieldSchema;

    @Schema(description = "不可变快照行（按 rowOrder 升序）")
    private List<Row> rows;

    @Schema(description = "同一填报实例上一次提交的编号；首次提交为空（供审批人对比整改前后差异）")
    private Long previousSubmissionId;

    @Schema(description = "提交时产生的警告提示（仅当前有效提交返回；警告不阻断提交）")
    private List<FillSubmitRespVO.Issue> warnings;

    @Schema(description = "管理后台 - 快照行")
    @Data
    public static class Row {

        @Schema(description = "稳定行标识")
        private String rowId;

        @Schema(description = "显示排序")
        private BigDecimal rowOrder;

        @Schema(description = "行数据：fieldCode -> 值")
        private Map<String, Object> data;

    }

}
