package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 主数据模型 Response VO")
@Data
public class MasterModelRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "主数据目录编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    private Long catalogId;

    private Long domainId;

    private String domainName;

    private Integer version;

    private Boolean approvalEnabled;

    private String approvalProcessKey;

    private String publishState;

    private String publishMessage;

    /** 启用规则的自动编码字段，供维护页省略手工填写。 */
    private String enabledCodeField;

    /** 数据源产品是否支持已验证的原子条件维护；无法确认时为 false。 */
    private Boolean maintenanceSupported;

    /** 无法维护的安全提示，不包含数据源凭据或 RPC 异常详情。 */
    private String maintenanceUnsupportedReason;

    @Schema(description = "当前数据源可使用的筛选操作符；字段类型仍需进一步限制")
    private List<String> queryFilterOperators;

    @Schema(description = "物理表所在数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "数据源名称", example = "中台库")
    private String databaseName;

    @Schema(description = "数据源产品类型编码")
    private String databaseType;

    @Schema(description = "数据库地址，仅主机和端口，不包含账号或连接参数")
    private String databaseHost;

    @Schema(description = "最近成功生效的主数据维护时间；待审批、失败及结果不确定的变更不计入")
    private LocalDateTime lastMaintenanceTime;

    @Schema(description = "物理表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "md_person")
    private String tableName;

    @Schema(description = "中文名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据")
    private String tableCn;

    @Schema(description = "描述", example = "全市统一人口主数据")
    private String description;

    @Schema(description = "状态：0-草稿 1-已发布 2-已下线", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer status;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "发布人编号", example = "1")
    private Long releaseUserId;

    @Schema(description = "发布人昵称", example = "管理员")
    private String releaseUserName;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建人审计标识")
    private String creator;

    @Schema(description = "创建人昵称")
    private String creatorName;

    @Schema(description = "当前模型定义字段数")
    private Long fieldCount;

    @Schema(description = "字段列表")
    private List<MasterColumnVO> columns;

}
