package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - API 服务 Response VO")
@Data
public class DataApiRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "服务分类编号", example = "1")
    private Long categoryId;

    @Schema(description = "API名称", example = "站点流量实时数据服务")
    private String name;

    @Schema(description = "API版本", example = "v1.0.0")
    private String version;

    @Schema(description = "API地址（相对路径）", example = "/api/water/level/hourly/list")
    private String path;

    @Schema(description = "请求方式：GET/POST", example = "POST")
    private String method;

    @Schema(description = "返回格式：PAGE/LIST/DETAIL", example = "PAGE")
    private String resultFormat;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "IP名单")
    private String ipWhitelist;

    @Schema(description = "IP规则类型：WHITE-白名单 BLACK-黑名单", example = "WHITE")
    private String ipRuleType;

    @Schema(description = "是否限流：0-否 1-是", example = "0")
    private Integer limitEnabled;

    @Schema(description = "限流窗口（秒）", example = "60")
    private Integer limitSeconds;

    @Schema(description = "窗口内限制请求次数", example = "5")
    private Integer limitCount;

    @Schema(description = "状态：0-下线 1-上线", example = "1")
    private Integer status;

    @Schema(description = "接口类型：QUERY/INSERT/UPDATE/DELETE/MULTI", example = "QUERY")
    private String apiType;

    @Schema(description = "认证方式：NONE/APPKEY/SIGN", example = "NONE")
    private String authType;

    @Schema(description = "是否开启结果缓存：0-否 1-是", example = "0")
    private Integer cacheEnabled;

    @Schema(description = "缓存时长（秒）", example = "60")
    private Integer cacheSeconds;

    @Schema(description = "是否开启熔断：0-否 1-是", example = "0")
    private Integer breakerEnabled;

    @Schema(description = "熔断阈值：窗口内异常次数", example = "10")
    private Integer breakerErrorCount;

    @Schema(description = "熔断统计时间窗口（秒）", example = "60")
    private Integer breakerWindowSeconds;

    @Schema(description = "熔断时长（秒）", example = "60")
    private Integer breakerOpenSeconds;

    @Schema(description = "是否开启告警通知：0-否 1-是", example = "0")
    private Integer alarmEnabled;

    @Schema(description = "配置方式：TABLE/SQL/FORWARD", example = "TABLE")
    private String configType;

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "数据表名", example = "hyd_discharge")
    private String tableName;

    private Long etlBatchId;

    private String etlBatchHash;

    private String etlSchemaName;

    @Schema(description = "SQL 脚本")
    private String sqlText;

    @Schema(description = "转发地址")
    private String forwardUrl;

    @Schema(description = "转发请求方式")
    private String forwardMethod;

    @Schema(description = "请求参数定义")
    private List<DataApiSaveReqVO.ParamItem> requestParams;

    @Schema(description = "返回字段定义")
    private List<DataApiSaveReqVO.FieldItem> responseFields;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    private Long providerDeptId;

    private String resourceRef;

    private Long ownerUserId;

    private Long activeReleaseId;

    private Integer draftRevision;

    private Boolean unpublishedDraft;

    private String contractHash;

    private Integer rowVersion;

}
