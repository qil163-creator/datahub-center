package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-草稿操作应用 Request VO
 *
 * 协同协议的最小单元：目标 = workItemId + rowId + fieldCode；
 * expectedVersion 为客户端已同步到的服务端版本，不匹配即拒绝（禁止静默覆盖）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 草稿操作应用 Request VO")
@Data
public class FillOperationApplyReqVO {

    @Schema(description = "客户端已同步到的服务端版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "135")
    @NotNull(message = "expectedVersion 不能为空")
    private Long expectedVersion;

    @Schema(description = "客户端会话标识（协同定位用）", example = "sess-1a2b3c")
    private String clientSessionId;

    @Schema(description = "操作列表（按顺序应用）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "操作列表不能为空")
    @Valid
    private List<OperationItem> operations;

    @Schema(description = "管理后台 - 草稿操作项")
    @Data
    public static class OperationItem {

        @Schema(description = "客户端操作号（唯一，网络重试去重）", requiredMode = Schema.RequiredMode.REQUIRED, example = "op-8f2a1b3c")
        @NotEmpty(message = "clientOpId 不能为空")
        private String clientOpId;

        @Schema(description = "操作类型：SET_CELL/ADD_ROW/REMOVE_ROW", requiredMode = Schema.RequiredMode.REQUIRED, example = "SET_CELL")
        @NotEmpty(message = "opType 不能为空")
        private String opType;

        @Schema(description = "目标行标识（SET_CELL/REMOVE_ROW 必填；ADD_ROW 为新行标识，受控客户端生成）", example = "c-8f2a1b")
        private String rowId;

        @Schema(description = "目标字段码（SET_CELL 必填）", example = "qty")
        private String fieldCode;

        @Schema(description = "新值（SET_CELL 为单元格值；ADD_ROW 可携带初始行数据对象）")
        private Object value;

        @Schema(description = "插入位置的前一行（ADD_ROW 用，空表示追加到末尾）", example = "c-7e1d0a")
        private String afterRowId;

    }

}
