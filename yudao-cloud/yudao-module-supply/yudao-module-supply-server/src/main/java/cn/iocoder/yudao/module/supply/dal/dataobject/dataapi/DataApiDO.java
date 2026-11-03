package cn.iocoder.yudao.module.supply.dal.dataobject.dataapi;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.supply.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据服务-API 服务 DO
 *
 * @author Li
 */
@TableName(value = "dh_supply_api", autoResultMap = true)
@KeySequence("dh_supply_api_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataApiDO extends BaseDO {

    /** 状态：下线 */
    public static final int STATUS_OFFLINE = 0;

    /** 状态：上线 */
    public static final int STATUS_ONLINE = 1;

    /** 配置方式：单表向导式 */
    public static final String CONFIG_TYPE_TABLE = "TABLE";

    /** 配置方式：SQL 脚本式 */
    public static final String CONFIG_TYPE_SQL = "SQL";

    /** 配置方式：第三方转发 */
    public static final String CONFIG_TYPE_FORWARD = "FORWARD";

    /** 配置方式：指定 ETL 成果批次，逐次校验提交与质量证据。 */
    public static final String CONFIG_TYPE_ETL_BATCH = "ETL_BATCH";

    /** 返回格式：分页 */
    public static final String RESULT_FORMAT_PAGE = "PAGE";

    /** 返回格式：列表 */
    public static final String RESULT_FORMAT_LIST = "LIST";

    /** 返回格式：详情 */
    public static final String RESULT_FORMAT_DETAIL = "DETAIL";

    /** 接口类型：查询 */
    public static final String API_TYPE_QUERY = "QUERY";

    /** 接口类型：插入 */
    public static final String API_TYPE_INSERT = "INSERT";

    /** 接口类型：更新 */
    public static final String API_TYPE_UPDATE = "UPDATE";

    /** 接口类型：删除 */
    public static final String API_TYPE_DELETE = "DELETE";

    /** 接口类型：多脚本（事务） */
    public static final String API_TYPE_MULTI = "MULTI";

    /** 认证方式：无认证 */
    public static final String AUTH_TYPE_NONE = "NONE";

    /** 认证方式：AppKey 认证 */
    public static final String AUTH_TYPE_APPKEY = "APPKEY";

    /** 认证方式：签名认证（国密 SM3） */
    public static final String AUTH_TYPE_SIGN = "SIGN";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 服务分类编号
     */
    private Long categoryId;

    /**
     * API名称
     */
    private String name;

    /**
     * API版本，如 v1.0.0
     */
    private String version;

    /**
     * API地址（相对路径）
     */
    private String path;

    /**
     * 请求方式：GET/POST
     */
    private String method;

    /**
     * 返回格式：PAGE-分页 LIST-列表 DETAIL-详情
     */
    private String resultFormat;

    /**
     * 描述
     */
    private String description;

    /**
     * IP名单（多个用英文逗号分隔，空不限制）
     */
    private String ipWhitelist;

    /**
     * IP规则类型：WHITE-白名单 BLACK-黑名单
     */
    private String ipRuleType;

    /**
     * 是否限流：0-否 1-是
     */
    private Integer limitEnabled;

    /**
     * 限流窗口（秒）
     */
    private Integer limitSeconds;

    /**
     * 窗口内限制请求次数
     */
    private Integer limitCount;

    /**
     * 状态：0-下线 1-上线
     */
    private Integer status;

    /**
     * 接口类型：QUERY/INSERT/UPDATE/DELETE/MULTI
     */
    private String apiType;

    /**
     * 认证方式：NONE/APPKEY/SIGN
     */
    private String authType;

    /**
     * 是否开启结果缓存：0-否 1-是（仅查询类）
     */
    private Integer cacheEnabled;

    /**
     * 缓存时长（秒）
     */
    private Integer cacheSeconds;

    /**
     * 是否开启熔断：0-否 1-是
     */
    private Integer breakerEnabled;

    /**
     * 熔断阈值：窗口内异常次数
     */
    private Integer breakerErrorCount;

    /**
     * 熔断统计时间窗口（秒）
     */
    private Integer breakerWindowSeconds;

    /**
     * 熔断时长（秒）
     */
    private Integer breakerOpenSeconds;

    /**
     * 是否开启告警通知：0-否 1-是
     */
    private Integer alarmEnabled;

    /**
     * 配置方式：TABLE-单表向导式 SQL-SQL脚本式 FORWARD-第三方转发
     */
    private String configType;

    /**
     * 数据源编号（TABLE/SQL 模式）
     */
    private Long databaseId;

    /**
     * 数据表名（TABLE 模式）
     */
    private String tableName;

    private Long etlBatchId;

    private String etlBatchHash;

    private String etlSchemaName;

    /**
     * SQL 脚本（SQL 模式，参数用 #{参数名} 占位）
     */
    private String sqlText;

    /**
     * 转发地址（FORWARD 模式）
     */
    private String forwardUrl;

    /**
     * 转发请求方式（FORWARD 模式，空则同 method）
     */
    private String forwardMethod;

    /**
     * 请求参数定义 JSON 数组
     */
    @TableField(value = "request_params", typeHandler = JsonbStringTypeHandler.class)
    private String requestParamsJson;

    /**
     * 返回字段定义 JSON 数组
     */
    @TableField(value = "response_fields", typeHandler = JsonbStringTypeHandler.class)
    private String responseFieldsJson;

    /**
     * 备注
     */
    private String remark;

    private Long providerDeptId;

    private String resourceRef;

    private Long ownerUserId;

    private Long activeReleaseId;

    private Integer draftRevision;

    @TableField(value = "draft_json", typeHandler = JsonbStringTypeHandler.class)
    private String draftJson;

    private String contractHash;

    private Integer rowVersion;

}
