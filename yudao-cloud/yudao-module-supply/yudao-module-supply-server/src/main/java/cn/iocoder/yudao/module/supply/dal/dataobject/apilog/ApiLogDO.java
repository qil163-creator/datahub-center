package cn.iocoder.yudao.module.supply.dal.dataobject.apilog;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.supply.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据服务-API 调用记录 DO
 *
 * @author Li
 */
@TableName(value = "dh_supply_api_log", autoResultMap = true)
@KeySequence("dh_supply_api_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiLogDO extends BaseDO {

    /** 状态：失败 */
    public static final int STATUS_FAILED = 0;

    /** 状态：成功 */
    public static final int STATUS_SUCCESS = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * API服务编号
     */
    private Long apiId;

    /**
     * API服务名称（冗余）
     */
    private String apiName;

    /**
     * 服务分类编号（冗余）
     */
    private Long categoryId;

    /**
     * 调用应用编号（可空）
     */
    private Long appId;

    /**
     * 调用者IP
     */
    private String callerIp;

    /**
     * 调用接口地址
     */
    private String callPath;

    /**
     * 请求方式
     */
    private String method;

    /**
     * 调用参数 JSON
     */
    @TableField(value = "params_json", typeHandler = JsonbStringTypeHandler.class)
    private String paramsJson;

    /**
     * 调用时间
     */
    private LocalDateTime callTime;

    /**
     * 调用耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 调用数据量
     */
    private Long dataCount;

    /**
     * 状态：1-成功 0-失败
     */
    private Integer status;

    /**
     * 异常信息记录
     */
    private String errorInfo;

    /**
     * 执行场景：DRAFT_READONLY_PREVIEW / ADMIN_EXECUTE / APP_GATEWAY
     */
    private String callMode;

    /**
     * 测试运行编号（草稿试调）
     */
    private String testRunNo;

    private Long releaseId;

    private String rejectStage;

    private String traceId;

}
