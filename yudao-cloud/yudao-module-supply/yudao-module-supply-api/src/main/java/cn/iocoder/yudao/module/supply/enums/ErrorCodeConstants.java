package cn.iocoder.yudao.module.foundation.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 *  错误码 Core 枚举类
 *
 * pay 系统，使用 1_500_000_000 段
 */
public interface ErrorCodeConstants {

    // ========== 数据服务类目管理 1_500_012_000 ==========
    ErrorCode CATEGORY_DATA_SUPPLY_NOT_EXISTS = new ErrorCode(1_500_012_001, "数据服务类目管理不存在");
    ErrorCode CATEGORY_DATA_SUPPLY_EXITS_CHILDREN = new ErrorCode(1_500_012_002, "存在存在子数据服务类目管理，无法删除");
    ErrorCode CATEGORY_DATA_SUPPLY_PARENT_NOT_EXITS = new ErrorCode(1_500_012_003, "父级数据服务类目管理不存在");
    ErrorCode CATEGORY_DATA_SUPPLY_PARENT_ERROR = new ErrorCode(1_500_012_004, "不能设置自己为父数据服务类目管理");
    ErrorCode CATEGORY_DATA_SUPPLY_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_012_005,
            "已经存在该类目名称的数据服务类目管理");
    ErrorCode CATEGORY_DATA_SUPPLY_PARENT_IS_CHILD = new ErrorCode(1_500_012_006,
            "不能设置自己的子CategoryDataSupply为父CategoryDataSupply");

    // ========== 数据服务-API 服务 1_500_040_000 ==========
    ErrorCode DATA_API_NOT_EXISTS = new ErrorCode(1_500_040_000, "API 服务不存在");
    ErrorCode DATA_API_PATH_DUPLICATE = new ErrorCode(1_500_040_001, "相同版本下已存在相同地址的 API 服务");
    ErrorCode DATA_API_RELEASED_CANNOT_MODIFY = new ErrorCode(1_500_040_002, "API 服务已上线，请先下线后再修改");
    ErrorCode DATA_API_NOT_RELEASED = new ErrorCode(1_500_040_003, "API 服务未上线");
    ErrorCode DATA_API_DATABASE_NOT_EXISTS = new ErrorCode(1_500_040_004, "API 服务绑定的数据源不存在");
    ErrorCode DATA_API_CONFIG_INVALID = new ErrorCode(1_500_040_005, "API 服务配置不完整：{}");
    ErrorCode DATA_API_EXECUTE_FAILED = new ErrorCode(1_500_040_006, "API 服务执行失败：{}");
    ErrorCode DATA_API_IP_NOT_ALLOWED = new ErrorCode(1_500_040_007, "调用方 IP 不在白名单内");
    ErrorCode DATA_API_RATE_LIMITED = new ErrorCode(1_500_040_008, "请求过于频繁，已触发限流");
    ErrorCode DATA_API_PARAM_REQUIRED = new ErrorCode(1_500_040_009, "请求参数【{}】不能为空");
    ErrorCode DATA_API_AUTHORIZED_CANNOT_DELETE = new ErrorCode(1_500_040_010, "API 服务已被应用授权，无法删除");
    ErrorCode DATA_API_CIRCUIT_OPEN = new ErrorCode(1_500_040_011, "接口已熔断，请稍后重试");
    ErrorCode DATA_API_AUTH_FAILED = new ErrorCode(1_500_040_012, "接口认证失败：{}");
    ErrorCode DATA_API_NOT_AUTHORIZED = new ErrorCode(1_500_040_013, "应用未获得该 API 服务的有效授权");
    ErrorCode DATA_API_TYPE_NOT_SUPPORTED = new ErrorCode(1_500_040_014, "当前配置方式不支持该接口类型：{}");
    ErrorCode DATA_API_READONLY_REQUIRED = new ErrorCode(1_500_040_015, "当前执行仅允许只读查询：{}");
    ErrorCode DATA_API_PERMISSION_SUBJECT_INVALID = new ErrorCode(1_500_040_016, "授权主体不合法");
    // 1_500_040_017 原为“签名协议不允许”，签名统一为单一 HMAC-SM3 协议后退役，不得复用。
    ErrorCode DATA_API_REQUEST_INVALID = new ErrorCode(1_500_040_018, "请求内容不合法：{}");
    ErrorCode DATA_API_PUBLISH_CONFLICT = new ErrorCode(1_500_040_019, "发布冲突，请刷新后重试");
    ErrorCode DATA_API_RELEASE_NOT_EXISTS = new ErrorCode(1_500_040_020, "发布快照不存在");
    ErrorCode DATA_API_CONTRACT_MISMATCH = new ErrorCode(1_500_040_021, "授权合同与当前发布不一致");
    ErrorCode DATA_API_ROLLBACK_DENIED = new ErrorCode(1_500_040_022, "不能回滚到该发布：{}");

    // ========== 数据服务-调用记录 1_500_041_000 ==========
    ErrorCode API_LOG_NOT_EXISTS = new ErrorCode(1_500_041_000, "调用记录不存在");

    // ========== 数据服务-应用管理 1_500_042_000 ==========
    ErrorCode SUPPLY_APP_NOT_EXISTS = new ErrorCode(1_500_042_000, "应用不存在");
    ErrorCode SUPPLY_APP_NAME_DUPLICATE = new ErrorCode(1_500_042_001, "已存在同名应用");
    ErrorCode SUPPLY_APP_API_NOT_EXISTS = new ErrorCode(1_500_042_002, "应用 API 授权记录不存在");
    ErrorCode SUPPLY_APP_API_DUPLICATE = new ErrorCode(1_500_042_003, "该应用已授权此 API 服务");
    ErrorCode SUPPLY_APP_GRANT_SUBJECT_INVALID = new ErrorCode(1_500_042_004, "不能将应用授权绑定到该权限主体");
    ErrorCode SUPPLY_APP_DISABLED = new ErrorCode(1_500_042_005, "应用已暂停或撤销");
    ErrorCode SUPPLY_GRANT_OP_CONFLICT = new ErrorCode(1_500_042_006, "相同操作编号的内容冲突");
    ErrorCode SUPPLY_GRANT_OP_NOT_EXISTS = new ErrorCode(1_500_042_007, "授权操作结果不存在");
    ErrorCode SUPPLY_GRANT_STALE = new ErrorCode(1_500_042_008, "过期的授权决定不能覆盖更新的撤销");

}
