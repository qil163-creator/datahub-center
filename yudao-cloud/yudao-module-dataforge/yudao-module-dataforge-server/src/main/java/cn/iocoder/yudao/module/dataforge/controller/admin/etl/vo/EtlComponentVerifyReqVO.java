package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 - ETL 组件校验 Request VO。
 *
 * <p>qData 组件校验接口只提交当前组件及参数；为了让设计器在保存前复用同一套
 * DAG 规则，本 VO 也允许提交完整画布。完整画布存在时会优先校验画布。</p>
 */
@Schema(description = "管理后台 - ETL 组件校验 Request VO")
@Data
public class EtlComponentVerifyReqVO {

    @Schema(description = "组件类型或本平台 toolCode", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "table-input")
    @NotBlank(message = "组件类型不能为空")
    @Size(max = 100, message = "组件类型不能超过 100 个字符")
    private String componentType;

    @Schema(description = "组件节点编号（单组件校验时使用）", example = "node-1")
    @Size(max = 100, message = "节点编号不能超过 100 个字符")
    private String nodeId;

    @Schema(description = "组件名称", example = "客户表输入")
    @Size(max = 200, message = "组件名称不能超过 200 个字符")
    private String nodeName;

    @Schema(description = "ETL 节点类型：1-输入 2-转换 3-输出 4-脚本；省略时按组件类型推断")
    private Integer toolType;

    @Schema(description = "组件参数；不会原样记录或写入日志")
    @Size(max = 100, message = "组件参数不能超过 100 个字段")
    private Map<String, Object> taskParams;

    @Schema(description = "完整画布节点（可选）")
    @Size(max = 32, message = "轻量 ETL 最多 32 个节点")
    private List<Map<String, Object>> nodes;

    @Schema(description = "完整画布连线（可选）")
    @Size(max = 128, message = "轻量 ETL 最多 128 条连线")
    private List<Map<String, Object>> edges;

}
