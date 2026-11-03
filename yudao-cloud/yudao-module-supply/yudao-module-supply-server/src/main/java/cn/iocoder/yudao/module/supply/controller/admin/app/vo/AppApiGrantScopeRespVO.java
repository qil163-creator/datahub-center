package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用 API 授权的“实际范围”视图：按权威读取路径呈现查询端真正会用到的字段与各来源状态。
 * 只输出授权事实，不包含 SQL、连接串、密钥或持久载荷原文。
 */
@Schema(description = "管理后台 - 应用 API 授权实际范围 Response VO")
@Data
public class AppApiGrantScopeRespVO {

    /** 聚合当前可用（v2 来源有效，或旧协议授权仍在有效期内）。 */
    public static final String MODE_ACTIVE = "ACTIVE";

    /** 当前没有有效授权：来源全部撤销/暂停/到期，或应用、API、主体已停用。 */
    public static final String MODE_NONE = "NONE";

    /** 管理端整体禁用；来源记录保留，解除禁用后按来源账本恢复。 */
    public static final String MODE_DISABLED = "DISABLED";

    /** 证据无法确认，必须人工复核；不得按当前元数据补齐成可用授权。 */
    public static final String MODE_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    @Schema(description = "授权关系编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "应用编号", example = "1")
    private Long appId;

    @Schema(description = "API 服务编号", example = "10")
    private Long apiId;

    @Schema(description = "当前模式：ACTIVE/NONE/DISABLED/REVIEW_REQUIRED", example = "ACTIVE")
    private String mode;

    @Schema(description = "聚合控制版本；控制开关必须原样回传，禁止有损数值转换", example = "3")
    private Long aggregateRevision;

    @Schema(description = "来源协议版本：2 为来源账本协议，0 为旧协议", example = "2")
    private Integer protocolVersion;

    /**
     * true 表示查询在连接数据库之前会被限制到 approvedColumns；
     * false 表示旧协议没有冻结字段证据，空列表必须显示为“字段未受限”，不能读成“批准了零个字段”。
     */
    @Schema(description = "查询是否按批准字段受限", example = "true")
    private Boolean fieldScoped;

    @Schema(description = "当前实际生效的批准字段（各有效来源的并集）")
    private List<String> approvedColumns;

    @Schema(description = "阻断原因；ACTIVE 且字段受限时为空", example = "AGGREGATE_DISABLED")
    private String blockReason;

    @Schema(description = "逐来源状态；管理端据此判断解除禁用或撤销后的结果")
    private List<GrantSourceRespVO> sources;

    @Schema(description = "管理后台 - 授权来源")
    @Data
    public static class GrantSourceRespVO {

        @Schema(description = "来源系统", example = "asset")
        private String sourceSystem;

        @Schema(description = "来源业务身份", example = "apply:1024")
        private String sourceKey;

        @Schema(description = "来源修订号", example = "3")
        private Long sourceRevision;

        @Schema(description = "来源状态：ACTIVE/SUSPENDED/REVOKED/REVIEW_REQUIRED", example = "ACTIVE")
        private String status;

        @Schema(description = "是否永久有效", example = "false")
        private Boolean forever;

        @Schema(description = "生效起点（含）")
        private LocalDateTime validFrom;

        @Schema(description = "生效终点（不含）")
        private LocalDateTime validTo;

        @Schema(description = "当前是否计入实际生效字段", example = "true")
        private Boolean contributing;

        @Schema(description = "该来源冻结的批准字段")
        private List<String> approvedColumns;
    }
}
