package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - API 服务保存 Request VO")
@Data
public class DataApiSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "服务分类编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "API 类目不能为空")
    private Long categoryId;

    @Schema(description = "API名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "站点流量实时数据服务")
    @NotBlank(message = "API 名称不能为空")
    @Size(max = 100, message = "API 名称不能超过 100 个字符")
    private String name;

    @Schema(description = "API版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "v1.0.0")
    @NotBlank(message = "API 版本不能为空")
    @Size(max = 20, message = "API 版本不能超过 20 个字符")
    private String version;

    @Schema(description = "API地址（相对路径）", requiredMode = Schema.RequiredMode.REQUIRED, example = "/api/water/level/hourly/list")
    @NotBlank(message = "API 地址不能为空")
    @Size(max = 200, message = "API 地址不能超过 200 个字符")
    private String path;

    @Schema(description = "请求方式：GET/POST", requiredMode = Schema.RequiredMode.REQUIRED, example = "POST")
    @NotBlank(message = "请求方式不能为空")
    private String method;

    @Schema(description = "返回格式：PAGE-分页 LIST-列表 DETAIL-详情", requiredMode = Schema.RequiredMode.REQUIRED, example = "PAGE")
    @NotBlank(message = "返回格式不能为空")
    private String resultFormat;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "IP名单（多个用英文逗号分隔，空不限制）")
    @Size(max = 500, message = "IP 名单不能超过 500 个字符")
    private String ipWhitelist;

    @Schema(description = "IP规则类型：WHITE-白名单 BLACK-黑名单", example = "WHITE")
    private String ipRuleType;

    @Schema(description = "是否限流：0-否 1-是", example = "0")
    private Integer limitEnabled;

    @Schema(description = "限流窗口（秒）", example = "60")
    private Integer limitSeconds;

    @Schema(description = "窗口内限制请求次数", example = "5")
    private Integer limitCount;

    @Schema(description = "状态：0-下线 1-上线", example = "0")
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

    @Schema(description = "配置方式：TABLE/SQL/FORWARD", requiredMode = Schema.RequiredMode.REQUIRED, example = "TABLE")
    @NotBlank(message = "配置方式不能为空")
    private String configType;

    @Schema(description = "数据源编号（TABLE/SQL 模式）", example = "1")
    private Long databaseId;

    @Schema(description = "数据表名（TABLE 模式）", example = "hyd_discharge")
    private String tableName;

    @Schema(description = "指定 ETL 成果批次编号，禁止按最新检测结果替代")
    @jakarta.validation.constraints.Positive
    private Long etlBatchId;

    @Schema(description = "发布时服务端冻结的批次摘要")
    private String etlBatchHash;

    @Schema(description = "发布时服务端冻结的目标 schema")
    private String etlSchemaName;

    @Schema(description = "SQL 脚本（SQL 模式，参数用 #{参数名} 占位）")
    private String sqlText;

    @Schema(description = "转发地址（FORWARD 模式）")
    @Size(max = 500, message = "转发地址不能超过 500 个字符")
    private String forwardUrl;

    @Schema(description = "转发请求方式（FORWARD 模式）", example = "POST")
    private String forwardMethod;

    @Schema(description = "请求参数定义")
    private List<ParamItem> requestParams;

    @Schema(description = "返回字段定义")
    private List<FieldItem> responseFields;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "目录资源稳定引用")
    @Size(max = 128, message = "资源引用不能超过 128 个字符")
    private String resourceRef;

    @Schema(description = "提供部门编号")
    private Long providerDeptId;

    @Schema(description = "API 负责人")
    private Long ownerUserId;

    @Schema(description = "乐观锁版本")
    private Integer rowVersion;

    @Schema(description = "请求参数定义项")
    @Data
    public static class ParamItem {

        @Schema(description = "参数名称", example = "station_code")
        private String name;

        @Schema(description = "是否允许为空：0-否 1-是", example = "0")
        private Integer nullable;

        @Schema(description = "描述", example = "站点编码")
        private String description;

        @Schema(description = "参数类型：STRING/INTEGER/DOUBLE/DATE", example = "STRING")
        private String paramType;

        @Schema(description = "示例值", example = "ST0100")
        private String exampleValue;

        @Schema(description = "默认值", example = "ST0100")
        private String defaultValue;

    }

    @Schema(description = "返回字段定义项")
    @Data
    public static class FieldItem {

        @Schema(description = "列名", example = "station_code")
        private String name;

        @Schema(description = "输出名称（参数名映射，空则用列名）", example = "stationCode")
        private String outputName;

        @Schema(description = "中文名称", example = "站点编码")
        private String cnName;

        @Schema(description = "描述")
        private String description;

        @Schema(description = "数据类型", example = "VARCHAR")
        private String dataType;

        @Schema(description = "时间格式", example = "yyyy-MM-dd HH:mm:ss")
        private String timeFormat;

        @Schema(description = "示例值")
        private String exampleValue;

        @Schema(description = "数据模型：HIDE-字段隐藏 CONCAT-内容拼接 REPLACE-内容替换 " +
                "SUBSTRING-内容截取 SPLIT-内容分割 DATE_FORMAT-时间格式化", example = "SUBSTRING")
        private String model;

        @Schema(description = "模型规则（拼接如 ${value}_后缀；替换如 旧值,新值；截取如 0,2；分割如 @；格式化如 yyyy-MM-dd）")
        private String modelRule;

        @Schema(description = "是否脱敏：0-否 1-是（保留首尾，中间星号）", example = "0")
        private Integer desensitize;

    }

}
