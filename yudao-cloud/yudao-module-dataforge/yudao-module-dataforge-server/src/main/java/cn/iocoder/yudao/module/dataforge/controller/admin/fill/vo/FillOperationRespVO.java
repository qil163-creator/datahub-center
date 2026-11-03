package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-草稿操作 Response VO（增量拉取，协同重放）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 草稿操作 Response VO")
@Data
public class FillOperationRespVO {

    @Schema(description = "服务端版本", example = "136")
    private Long serverVersion;

    @Schema(description = "客户端操作号", example = "op-8f2a1b3c")
    private String clientOpId;

    @Schema(description = "客户端会话标识", example = "sess-1a2b3c")
    private String clientSessionId;

    @Schema(description = "操作类型", example = "SET_CELL")
    private String opType;

    @Schema(description = "目标行标识", example = "c-8f2a1b")
    private String rowId;

    @Schema(description = "目标字段码", example = "qty")
    private String fieldCode;

    @Schema(description = "新值（JSON 解析后的对象）")
    private Object newValue;

    @Schema(description = "操作人编号", example = "1001")
    private Long operatorUserId;

    @Schema(description = "操作时间")
    private LocalDateTime createTime;

}
