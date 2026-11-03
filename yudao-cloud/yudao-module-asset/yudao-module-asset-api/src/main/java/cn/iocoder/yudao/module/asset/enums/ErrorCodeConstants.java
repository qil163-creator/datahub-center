package cn.iocoder.yudao.module.asset.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 资产模块错误码，沿用本模块既有的 1_500_* 共享号段。
 */
public interface ErrorCodeConstants {


    // ========== 标签类目管理 1_500_009_000 ==========
    ErrorCode CATEGORY_TAG_NOT_EXISTS = new ErrorCode(1_500_009_001, "标签类目管理不存在");
    ErrorCode CATEGORY_TAG_EXITS_CHILDREN = new ErrorCode(1_500_009_002, "存在存在子标签类目管理，无法删除");
    ErrorCode CATEGORY_TAG_PARENT_NOT_EXITS = new ErrorCode(1_500_009_003, "父级标签类目管理不存在");
    ErrorCode CATEGORY_TAG_PARENT_ERROR = new ErrorCode(1_500_009_004, "不能设置自己为父标签类目管理");
    ErrorCode CATEGORY_TAG_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_009_005, "已经存在该类目名称的标签类目管理");
    ErrorCode CATEGORY_TAG_PARENT_IS_CHILD = new ErrorCode(1_500_009_006, "不能设置自己的子CategoryTag为父CategoryTag");
    ErrorCode CATEGORY_TAG_IN_USE = new ErrorCode(1_500_009_007, "标签类目下仍存在标签，无法删除");

    // ========== 数据资产类目管理 1_500_011_000 ==========
    ErrorCode CATEGORY_DATA_ASSET_NOT_EXISTS = new ErrorCode(1_500_011_001, "数据资产类目管理不存在");
    ErrorCode CATEGORY_DATA_ASSET_EXITS_CHILDREN = new ErrorCode(1_500_011_002, "存在存在子数据资产类目管理，无法删除");
    ErrorCode CATEGORY_DATA_ASSET_PARENT_NOT_EXITS = new ErrorCode(1_500_011_003, "父级数据资产类目管理不存在");
    ErrorCode CATEGORY_DATA_ASSET_PARENT_ERROR = new ErrorCode(1_500_011_004, "不能设置自己为父数据资产类目管理");
    ErrorCode CATEGORY_DATA_ASSET_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_011_005,
            "已经存在该类目名称的数据资产类目管理");
    ErrorCode CATEGORY_DATA_ASSET_PARENT_IS_CHILD = new ErrorCode(1_500_011_006,
            "不能设置自己的子CategoryDataAsset为父CategoryDataAsset");
    ErrorCode CATEGORY_DATA_ASSET_IN_USE = new ErrorCode(1_500_011_007, "资产类目已被数据资产使用，无法删除");

    // ========== 数据资产目录 1_500_020_000 ==========
    ErrorCode DATA_ASSET_NOT_EXISTS = new ErrorCode(1_500_020_001, "数据资产不存在");
    ErrorCode DATA_ASSET_META_NOT_EXISTS = new ErrorCode(1_500_020_002, "表元数据不存在");
    ErrorCode DATA_ASSET_META_NOT_PUBLISHED = new ErrorCode(1_500_020_003, "表元数据尚未发布定版，不能编目");
    ErrorCode DATA_ASSET_META_DUPLICATE = new ErrorCode(1_500_020_004, "该表元数据已完成资产编目");
    ErrorCode DATA_ASSET_CATALOG_LINK_FAILED = new ErrorCode(1_500_020_005, "元数据目录挂接状态更新失败");
    ErrorCode DATA_ASSET_PUBLISH_CATEGORY_REQUIRED = new ErrorCode(1_500_020_006, "发布资产前必须选择资产类目");
    ErrorCode DATA_ASSET_DELETE_PUBLISHED = new ErrorCode(1_500_020_007, "已发布资产不能直接删除，请先取消发布");
    ErrorCode DATA_ASSET_PREVIEW_UNSUPPORTED = new ErrorCode(1_500_020_008, "当前资产类型不支持数据预览");
    ErrorCode DATA_ASSET_APPLY_REQUIRED = new ErrorCode(1_500_020_009, "已发布资产需要有效的共享申请才能查看数据");
    ErrorCode DATA_ASSET_TECH_UNAVAILABLE = new ErrorCode(1_500_020_100,
            "当前目录未挂接可用技术资源，不能预览或开通取数");
    ErrorCode DATA_ASSET_TECH_LINK_DUPLICATE = new ErrorCode(1_500_020_101, "该技术资源已挂接到当前目录条目");
    ErrorCode DATA_ASSET_MULTI_TABLE_AMBIGUOUS = new ErrorCode(1_500_020_102,
            "该目录条目关联多个技术资源，请使用新接口指定对象");
    ErrorCode DATA_ASSET_CATALOG_REVISION_CONFLICT = new ErrorCode(1_500_020_103, "目录内容已被他人更新，请刷新后重试");
    ErrorCode DATA_ASSET_FORBIDDEN = new ErrorCode(1_500_020_104, "无权维护其他部门的资源目录");
    ErrorCode DATA_ASSET_CATALOG_NOT_READY = new ErrorCode(1_500_020_105, "发布前缺少必备业务信息：{}");
    ErrorCode DATA_ASSET_VERSION_STATUS_DENY = new ErrorCode(1_500_020_106, "当前目录版本状态不允许该操作");
    ErrorCode DATA_ASSET_VERSION_NOT_EXISTS = new ErrorCode(1_500_020_107, "目录版本不存在");
    ErrorCode DATA_ASSET_TICKET_NOT_EXISTS = new ErrorCode(1_500_020_108, "校核工单不存在");
    ErrorCode DATA_ASSET_TICKET_STATUS_DENY = new ErrorCode(1_500_020_109, "当前校核工单状态不允许该操作");
    ErrorCode DATA_ASSET_QUALITY_DELETE_PENDING = new ErrorCode(1_500_020_110, "远端质量任务删除失败，已保留待删除状态");
    ErrorCode DATA_ASSET_RETIRE_PENDING = new ErrorCode(1_500_020_111,
            "资产仍有在途申请或未确认交付，请先完成停供及交付处置");
    ErrorCode DATA_ASSET_STOP_REASON_REQUIRED = new ErrorCode(1_500_020_112, "停止供数必须填写有效原因");
    ErrorCode DATA_ASSET_ETL_FILTER_UNSUPPORTED = new ErrorCode(1_500_020_113,
            "已关联 ETL 批次的资产请使用批次游标预览，暂不支持额外表过滤条件");

    ErrorCode DATA_ASSET_IMPORT_INPUT_INVALID = new ErrorCode(1_500_020_114, "迁入数据的字段、值或过滤条件无效");
    ErrorCode DATA_ASSET_IMPORT_ROW_NOT_EXISTS = new ErrorCode(1_500_020_115, "待维护的数据行不属于当前迁入资产");

    // ========== 资产标签 1_500_021_000 ==========
    ErrorCode ASSET_TAG_NOT_EXISTS = new ErrorCode(1_500_021_001, "资产标签不存在");
    ErrorCode ASSET_TAG_NAME_DUPLICATE = new ErrorCode(1_500_021_002, "同一类目下资产标签名称已存在");
    ErrorCode ASSET_TAG_IN_USE = new ErrorCode(1_500_021_003, "资产标签已被使用，无法删除");
    ErrorCode ASSET_TAG_DISABLED = new ErrorCode(1_500_021_004, "资产标签已停用，不能绑定到资产");
    ErrorCode ASSET_TAG_ENABLED_IMMUTABLE = new ErrorCode(1_500_021_130, "请先禁用资产标签，再修改或删除");
    ErrorCode ASSET_TAG_REVISION_CONFLICT = new ErrorCode(1_500_021_131, "标签或关联已变化，请刷新后重试");
    ErrorCode ASSET_TAG_INPUT_INVALID = new ErrorCode(1_500_021_132, "标签名称、分类、状态或修订号不合法");

    // ========== 资产质量任务 1_500_022_000 ==========
    ErrorCode ASSET_QUALITY_TASK_NOT_EXISTS = new ErrorCode(1_500_022_001, "资产质量任务不存在");
    ErrorCode ASSET_QUALITY_TASK_NAME_DUPLICATE = new ErrorCode(1_500_022_002, "该资产下已存在同名质量任务");
    ErrorCode ASSET_QUALITY_TASK_CRON_REQUIRED = new ErrorCode(1_500_022_003, "定时质量任务必须填写 Cron 表达式");
    ErrorCode ASSET_QUALITY_COLUMN_INVALID = new ErrorCode(1_500_022_004, "质量规则关联的字段不属于当前资产");
    ErrorCode ASSET_QUALITY_TASK_NOT_SYNCED = new ErrorCode(1_500_022_005, "资产质量任务尚未同步到质量引擎");
    ErrorCode ASSET_QUALITY_CONFIG_INVALID = new ErrorCode(1_500_022_020, "资产质量任务配置不合法：{}");
    ErrorCode ASSET_QUALITY_EXECUTION_UNKNOWN = new ErrorCode(1_500_022_021,
            "质量执行结果未知，已保留运行编号；请按原任务重试核对");

    // ========== 资产字段关联治理业务术语 1_500_024_000 ==========
    ErrorCode ASSET_TERM_NOT_EXISTS = new ErrorCode(1_500_024_001, "业务术语不存在");
    ErrorCode ASSET_TERM_DISABLED = new ErrorCode(1_500_024_004, "业务术语已停用，不能绑定到字段");
    ErrorCode ASSET_TERM_COLUMN_INVALID = new ErrorCode(1_500_024_005, "待绑定的字段不属于当前资产");

    // ========== 数据查询文件目录 1_500_025_000 ==========
    ErrorCode DATA_QUERY_FILE_NOT_EXISTS = new ErrorCode(1_500_025_001, "查询脚本不存在");
    ErrorCode DATA_QUERY_FILE_NAME_DUPLICATE = new ErrorCode(1_500_025_002, "同级目录下已存在同名节点");
    ErrorCode DATA_QUERY_FILE_PARENT_NOT_EXISTS = new ErrorCode(1_500_025_003, "父级目录不存在");
    ErrorCode DATA_QUERY_FILE_PARENT_ERROR = new ErrorCode(1_500_025_004, "不能设置自己为父节点");
    ErrorCode DATA_QUERY_FILE_PARENT_IS_CHILD = new ErrorCode(1_500_025_005, "不能把父节点设置到自己的子节点下");
    ErrorCode DATA_QUERY_FILE_PARENT_MUST_FOLDER = new ErrorCode(1_500_025_006, "父节点必须是目录");
    ErrorCode DATA_QUERY_FILE_HAS_CHILDREN = new ErrorCode(1_500_025_007, "目录下仍有子节点，无法删除");
    ErrorCode DATA_QUERY_FILE_TYPE_INVALID = new ErrorCode(1_500_025_008, "节点类型不正确");
    ErrorCode DATA_QUERY_FILE_TYPE_IMMUTABLE = new ErrorCode(1_500_025_009, "不能把目录和脚本互相转换");
    ErrorCode DATA_QUERY_FILE_FORBIDDEN = new ErrorCode(1_500_025_010, "只能修改或删除自己的目录和脚本");
    ErrorCode DATA_QUERY_FILE_VISIBILITY_INVALID = new ErrorCode(1_500_025_011, "可见范围只能是公开或私有");

    // ========== 数据集市 1_500_026_000 ==========
    ErrorCode MARKET_APPLY_NOT_EXISTS = new ErrorCode(1_500_026_001, "资源申请不存在");
    ErrorCode MARKET_APPLY_STATUS_DENY = new ErrorCode(1_500_026_002, "当前申请状态不允许该操作");
    ErrorCode MARKET_APPLY_FORBIDDEN = new ErrorCode(1_500_026_003, "无权查看或操作该申请");
    ErrorCode MARKET_APPLY_DUPLICATE = new ErrorCode(1_500_026_004, "该资产已有进行中的申请，请勿重复提交");
    ErrorCode MARKET_APPLY_ASSET_NOT_PUBLISHED = new ErrorCode(1_500_026_005, "只能申请已发布的数据资产");
    ErrorCode MARKET_APPLY_API_APP_REQUIRED = new ErrorCode(1_500_026_006, "申请开通 API 时必须选择应用和 API 服务");
    ErrorCode MARKET_APPLY_TIME_INVALID = new ErrorCode(1_500_026_007, "申请使用期限不合法");
    ErrorCode MARKET_APPLY_NOT_AUTHORIZED = new ErrorCode(1_500_026_011, "没有有效的集市授权，无法查询或预览该资产");
    ErrorCode MARKET_APPLY_PREVIEW_UNSUPPORTED = new ErrorCode(1_500_026_012,
            "当前资产类型不支持集市预览，请走数据服务 API");
    ErrorCode MARKET_APPLY_FIELD_SCOPE_INVALID = new ErrorCode(1_500_026_013,
            "申请字段范围无法匹配资源字段，已拒绝返回数据");
    ErrorCode MARKET_APPLY_API_NOT_BOUND = new ErrorCode(1_500_026_014, "所选 API 未绑定到该共享资源");
    ErrorCode MARKET_APPLY_APP_FORBIDDEN = new ErrorCode(1_500_026_015, "只能选择本人管理的应用开通 API");
    ErrorCode MARKET_APPLY_QUOTA_UNSUPPORTED = new ErrorCode(1_500_026_016,
            "消费额度无效或缺少冻结批准证据，请填写正确额度并重新提交审批");
    ErrorCode MARKET_APPLY_DEPT_INVALID = new ErrorCode(1_500_026_017, "申请部门必须是当前登录人所属部门");
    ErrorCode MARKET_QUERY_SQL_REJECTED = new ErrorCode(1_500_026_018, "查询语句超出已验证的单表 SELECT 范围");
    ErrorCode MARKET_APPLY_API_NOT_EXISTS = new ErrorCode(1_500_026_019, "申请开通的 API 服务不存在");
    ErrorCode MARKET_APPLY_API_TYPE_UNSUPPORTED = new ErrorCode(1_500_026_020,
            "本期不能开通该类型 API：仅支持已上线、单表向导、查询类且绑定当前资产库表的服务");
    ErrorCode MARKET_APPLY_API_ASSET_MISMATCH = new ErrorCode(1_500_026_021,
            "所选 API 未绑定当前资产的数据表，不能开通");
    ErrorCode MARKET_COMMENT_NOT_EXISTS = new ErrorCode(1_500_026_008, "资源评价不存在");
    ErrorCode MARKET_COMMENT_FORBIDDEN = new ErrorCode(1_500_026_009, "只能修改或删除自己的评价");
    ErrorCode MARKET_COMMENT_LEVEL_INVALID = new ErrorCode(1_500_026_010, "评分必须在 1 到 5 之间");
    ErrorCode MARKET_COMMENT_REQUEST_CONFLICT = new ErrorCode(1_500_026_034,
            "校核请求编号已用于其他内容，请刷新后重试");

    // ========== 供需对接 1_500_027_000 ==========
    ErrorCode MARKET_DEMAND_NOT_EXISTS = new ErrorCode(1_500_027_001, "数据需求不存在");
    ErrorCode MARKET_DEMAND_STATUS_DENY = new ErrorCode(1_500_027_002, "当前需求状态不允许该操作");
    ErrorCode MARKET_DEMAND_FORBIDDEN = new ErrorCode(1_500_027_003, "只能操作自己的需求");
    ErrorCode MARKET_DEMAND_MATCH_NOT_EXISTS = new ErrorCode(1_500_027_004, "需求匹配记录不存在");
    ErrorCode MARKET_DEMAND_MATCH_DUPLICATE = new ErrorCode(1_500_027_005, "该资产已匹配到当前需求");
    ErrorCode MARKET_DEMAND_REPLY_NOT_EXISTS = new ErrorCode(1_500_027_006, "需求响应不存在");
    ErrorCode MARKET_DEMAND_CLOSE_STATUS_INVALID = new ErrorCode(1_500_027_007,
            "关闭需求只能使用已关闭、已撤销或已驳回");
    ErrorCode MARKET_DEMAND_ACCEPT_DENIED = new ErrorCode(1_500_027_008, "只有完成交付的需求才能验收为已解决");

    ErrorCode MARKET_APPLY_SUPPLEMENT_REQUIRED = new ErrorCode(1_500_026_022, "请按补正要求修订后重新提交");
    ErrorCode MARKET_APPLY_NOT_EFFECTIVE = new ErrorCode(1_500_026_023, "申请尚未形成有效授权");
    ErrorCode MARKET_APPLY_SCOPE_UNCONFIRMED = new ErrorCode(1_500_026_024,
            "申请冻结字段或资产绑定证据缺失、不一致，请核查后重新申请");
    ErrorCode MARKET_APPLY_METADATA_UNAVAILABLE = new ErrorCode(1_500_026_025,
            "无法读取资源字段元数据，暂不能提交或开通该申请");
    ErrorCode MARKET_APPLY_RENEWAL_REQUIRED = new ErrorCode(1_500_026_026, "续期需要提交新的续期申请并重新审批");
    ErrorCode MARKET_APPLY_RENEWAL_CONFLICT = new ErrorCode(1_500_026_027,
            "续期请求编号缺失或与已有续期申请内容不一致");
    ErrorCode MARKET_QUERY_AUTHORIZATION_CHANGED = new ErrorCode(1_500_026_028,
            "查询期间共享授权或资产绑定已变化，结果未返回，请重新确认授权后查询");
    ErrorCode MARKET_OPERATION_NOT_PERSISTED = new ErrorCode(1_500_026_029,
            "操作记录未能完整保存，本次操作未生效，请稍后重试");
    ErrorCode MARKET_DELIVERY_DISPOSAL_DENIED = new ErrorCode(1_500_026_030,
            "当前交付证据不满足该处置条件，未修改授权；请重新核对原始证据");
    ErrorCode MARKET_APPLY_REVISION_CONFLICT = new ErrorCode(1_500_026_031,
            "申请已被他人更新，当前页面内容已过期，请刷新后重试");
    ErrorCode MARKET_DELIVERY_REDELIVER_DENIED = new ErrorCode(1_500_026_032,
            "交付受控重投条件不满足：仅完整匹配的撤销/暂停命令且已绑定核查决定才允许重投");
    ErrorCode MARKET_CHECK_SNAPSHOT_INVALID = new ErrorCode(1_500_026_033,
            "来源工单详情快照格式无效，请核查导入数据");

    // ========== 集市类目 1_500_028_000 ==========
    ErrorCode MARKET_CATEGORY_NOT_EXISTS = new ErrorCode(1_500_028_001, "集市类目不存在");
    ErrorCode MARKET_CATEGORY_PARENT_INVALID = new ErrorCode(1_500_028_002, "上级集市类目不存在或形成循环");
    ErrorCode MARKET_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_028_003, "同一上级下已存在该集市类目名称");
    ErrorCode MARKET_CATEGORY_HAS_CHILDREN = new ErrorCode(1_500_028_004, "集市类目存在未选中的下级，不能删除");
    ErrorCode MARKET_CATEGORY_IN_USE = new ErrorCode(1_500_028_005, "集市类目已挂载资源，不能删除");
    ErrorCode MARKET_CATEGORY_DISABLED = new ErrorCode(1_500_028_006, "集市类目或上级已停用，不能发布资源");
    ErrorCode MARKET_LISTING_INVALID = new ErrorCode(1_500_028_007, "集市发布信息无效或已过期，请刷新后重试");

    // ========== 资产字段分类清单 1_500_029_000 ==========
    ErrorCode ASSET_SENSITIVE_NOT_EXISTS = new ErrorCode(1_500_029_020, "敏感清单记录不存在");
    ErrorCode ASSET_SENSITIVE_ENABLED = new ErrorCode(1_500_029_021, "请先禁用敏感清单记录，再修改或删除");
    ErrorCode ASSET_SENSITIVE_REVISION_CONFLICT = new ErrorCode(1_500_029_022,
            "清单、资产或字段已发生变化，请刷新后重试");
    ErrorCode ASSET_SENSITIVE_COLUMN_INVALID = new ErrorCode(1_500_029_023, "所选字段不属于当前资产");
    ErrorCode ASSET_SENSITIVE_CATEGORY_INVALID = new ErrorCode(1_500_029_024, "所选分类不存在、已禁用或不是可选叶分类");
    ErrorCode ASSET_SENSITIVE_INPUT_INVALID = new ErrorCode(1_500_029_025, "敏感清单输入或修订号不合法");
}
