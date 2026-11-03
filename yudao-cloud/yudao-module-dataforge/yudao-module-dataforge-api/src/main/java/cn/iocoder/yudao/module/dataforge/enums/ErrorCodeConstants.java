package cn.iocoder.yudao.module.dataforge.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 *  错误码 Core 枚举类
 *
 */
public interface ErrorCodeConstants {

    // ========== 作业编排 1_500_091_000 ==========
    ErrorCode JOB_NOT_EXISTS = new ErrorCode(1_500_091_000, "作业不存在");
    ErrorCode JOB_CONFIG_INVALID = new ErrorCode(1_500_091_001, "作业配置不合法：{}");
    ErrorCode JOB_CONFLICT = new ErrorCode(1_500_091_002, "作业已被修改、名称重复或操作正在处理中，请刷新后重试");
    ErrorCode JOB_STATE_INVALID = new ErrorCode(1_500_091_003, "当前作业状态不允许该操作，请先关闭调度并下线，或等待运行结束");
    ErrorCode JOB_RUNTIME_FAILED = new ErrorCode(1_500_091_004, "调度器未确认操作结果，请查询原实例核对");


    // ========== 数据集成工作台 1_500_090_000 ==========
    ErrorCode INTEGRATION_TASK_NOT_EXISTS = new ErrorCode(1_500_090_000, "数据集成任务不存在");
    ErrorCode INTEGRATION_CONFIG_INVALID = new ErrorCode(1_500_090_001, "数据集成配置不合法：{}");
    ErrorCode INTEGRATION_CONFLICT = new ErrorCode(1_500_090_002, "任务已被修改，请刷新后重试");
    ErrorCode INTEGRATION_NAME_DUPLICATE = new ErrorCode(1_500_090_003, "同类型的数据集成任务名称已存在");
    ErrorCode INTEGRATION_STATE_INVALID = new ErrorCode(1_500_090_004, "任务状态不允许操作，请先关闭调度并下线任务");
    ErrorCode INTEGRATION_FLOW_NOT_READY = new ErrorCode(1_500_090_005,
            "任务尚未绑定本平台可执行流程；本次仅提供基础信息与源流程预览，暂不能执行或开启调度");
    // ========== 外部文件管理 1-500-016-020 ==========
    ErrorCode FILE_RESOURCE_CONNECTION_INVALID = new ErrorCode(1_500_016_020, "文件连接不存在、已停用或类型不支持");
    ErrorCode FILE_RESOURCE_CONFIG_INVALID = new ErrorCode(1_500_016_021,
            "文件连接配置无效，请检查根目录、端口和 WebHDFS 配置");
    ErrorCode FILE_RESOURCE_OPERATION_FAILED = new ErrorCode(1_500_016_022,
            "文件操作失败，请检查连接、权限及目标路径后重试");
    ErrorCode FILE_RESOURCE_PATH_INVALID = new ErrorCode(1_500_016_023, "文件路径或名称无效");
    ErrorCode FILE_RESOURCE_LIMIT_EXCEEDED = new ErrorCode(1_500_016_024, "文件大小或扫描数量超过当前操作上限");
    ErrorCode FILE_RESOURCE_RESULT_UNKNOWN = new ErrorCode(1_500_016_025, "操作结果尚未确认，请刷新目录核对后再操作");
    ErrorCode FILE_RESOURCE_DISABLED = new ErrorCode(1_500_016_026, "文件管理功能未启用，请联系管理员检查配置");


    // ========== 研发执行护栏 1_500_000_000 ==========
    ErrorCode OPERATION_PERMISSION_DENIED = new ErrorCode(1_500_000_001, "没有执行该操作的权限");
    ErrorCode DATABASE_UNAVAILABLE = new ErrorCode(1_500_000_003, "数据源不存在或已停用，不能用于研发任务");

    // ========== 数据源驱动 1_500_001_000 ==========
    ErrorCode DATASOURCE_DRIVER_NOT_EXISTS = new ErrorCode(1_500_001_000, "数据源驱动不存在");


    // ========== 数据源中心--数据库信息 1_500_003_000 ==========
    ErrorCode DATABASE_NOT_EXISTS = new ErrorCode(1_500_003_001, "数据源中心--数据库信息不存在");
    ErrorCode DATABASE_JDBC_URL_EMPTY = new ErrorCode(1_500_003_002, "数据库连接 URL 为空，无法测试连接");
    ErrorCode DATABASE_CONNECT_FAILED = new ErrorCode(1_500_003_003, "数据库连接失败：{}");
    ErrorCode DATABASE_TABLE_NOT_EXISTS = new ErrorCode(1_500_003_004, "数据源中的表不存在");
    ErrorCode DATABASE_PREVIEW_FAILED = new ErrorCode(1_500_003_005, "数据预览失败：{}");
    ErrorCode DATABASE_QUERY_SQL_INVALID = new ErrorCode(1_500_003_006, "查询 SQL 校验失败：{}");
    ErrorCode DATABASE_QUERY_FAILED = new ErrorCode(1_500_003_007, "查询执行失败：{}");
    ErrorCode DATABASE_MONITOR_RULE_NOT_EXISTS = new ErrorCode(1_500_003_009, "数据库告警规则不存在");
    ErrorCode DATABASE_MONITOR_RULE_INVALID = new ErrorCode(1_500_003_010, "告警规则指标或阈值无效");
    ErrorCode DATABASE_MONITOR_ALARM_NOT_EXISTS = new ErrorCode(1_500_003_011, "数据库告警记录不存在");
    ErrorCode DATABASE_MONITOR_ALARM_CONFLICT = new ErrorCode(1_500_003_012, "告警已处置，请刷新后查看");
    ErrorCode DATABASE_MONITOR_RANGE_INVALID = new ErrorCode(1_500_003_013, "监控范围仅支持近 7、15、30 天");
    ErrorCode DATABASE_NAMESPACE_INVALID = new ErrorCode(1_500_003_014, "数据源的库名或 schema 配置无效");


    // ========== 来源系统 1_500_004_000 ==========
    ErrorCode SYSTEM_SOURCE_NOT_EXISTS = new ErrorCode(1_500_004_000, "来源系统不存在");

    // ========== 数据库类型定义 1_500_005_000 ==========
    ErrorCode DATABASE_TYPE_NOT_EXISTS = new ErrorCode(1_500_005_000, "数据库类型不存在");

    // ========== 数据同步任务 1_500_013_000 ==========
    ErrorCode DATA_ACCESS_NOT_EXISTS = new ErrorCode(1_500_013_000, "数据同步任务不存在");
    ErrorCode DATA_ACCESS_CRON_INVALID = new ErrorCode(1_500_013_001,
            "cron 表达式无效（需为 6 位 Spring cron，例如 0 0 2 * * ?）：{}");
    ErrorCode DATA_ACCESS_SOURCE_DATABASE_NOT_EXISTS = new ErrorCode(1_500_013_002, "源端数据源不存在");
    ErrorCode DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS = new ErrorCode(1_500_013_003, "目标数据源不存在");
    ErrorCode DATA_ACCESS_TARGET_NOT_PLATFORM = new ErrorCode(1_500_013_004,
            "入中台库模式下，目标数据源必须是【中台库】用途的数据源");
    ErrorCode DATA_ACCESS_PRODUCT_TYPE_UNSUPPORTED = new ErrorCode(1_500_013_005,
            "数据库类型【{}】未能映射到同步引擎支持的产品类型");
    ErrorCode DATA_ACCESS_RELEASED_CANNOT_MODIFY = new ErrorCode(1_500_013_006, "任务已发布，请先取消发布");
    ErrorCode DATA_ACCESS_RUNNING = new ErrorCode(1_500_013_007, "任务正在运行中，请等待运行结束");
    ErrorCode DATA_ACCESS_NOT_RUNNING = new ErrorCode(1_500_013_008, "任务当前没有在运行");
    ErrorCode DATA_ACCESS_REALTIME_NOT_SUPPORTED = new ErrorCode(1_500_013_009, "暂不支持实时同步任务");
    ErrorCode DATA_ACCESS_SOURCE_SQL_BLANK = new ErrorCode(1_500_013_010, "SQL 接入必须填写源端 SQL（单条 SELECT）");
    ErrorCode DATA_ACCESS_TARGET_TABLE_BLANK = new ErrorCode(1_500_013_011, "SQL 接入必须指定目标表名");
    ErrorCode DATA_ACCESS_SQL_COLUMN_MAPPER_BLANK = new ErrorCode(1_500_013_012, "自定义 SQL 接入不允许映射字段为空");
    ErrorCode DATA_ACCESS_PREVIEW_PARAM_MISSING = new ErrorCode(1_500_013_013, "预览参数缺失：{}");
    ErrorCode DATA_ACCESS_ENGINE_NOT_SUPPORTED = new ErrorCode(1_500_013_014, "未知的同步引擎类型");
    ErrorCode DATA_ACCESS_SEATUNNEL_CONFIG_BLANK = new ErrorCode(1_500_013_015,
            "实时同步（Seatunnel）任务必须填写作业配置");
    ErrorCode DATA_ACCESS_SEATUNNEL_REQUEST_FAILED = new ErrorCode(1_500_013_016, "Seatunnel 集群请求失败：{}");
    ErrorCode DATA_ACCESS_REALTIME_ONLY_SEATUNNEL = new ErrorCode(1_500_013_017,
            "实时同步调度类型仅支持 Seatunnel 引擎");
    ErrorCode DATA_ACCESS_RUN_REQUEST_CONFLICT = new ErrorCode(1_500_013_018,
            "该请求标识已用于其他同步命令或对应记录已删除");
    ErrorCode DATA_ACCESS_RUN_PENDING_LIMIT = new ErrorCode(1_500_013_019, "该同步任务的等待队列已达上限");
    ErrorCode DATA_ACCESS_RUN_DEFINITION_CHANGED = new ErrorCode(1_500_013_020,
            "受理前同步配置或调度已变化，请重新加载");
    ErrorCode DATA_ACCESS_RUN_VERSION_CONFLICT = new ErrorCode(1_500_013_021, "同步运行状态已变化，请重新加载后操作");
    ErrorCode DATA_ACCESS_RUN_REVIEW_REQUIRED = new ErrorCode(1_500_013_022,
            "同步运行的退出和外部写入结果尚未完成核查");
    ErrorCode DATA_ACCESS_MAPPING_CONFIG_INVALID = new ErrorCode(1_500_013_023, "同步映射配置无效：{}");
    ErrorCode DATA_ACCESS_DATABASE_NAMESPACE_MISSING = new ErrorCode(1_500_013_024,
            "{}数据源缺少有效的库名或 schema，请完善数据源配置");
    ErrorCode DATA_ACCESS_DATABASE_PRODUCT_MISSING = new ErrorCode(1_500_013_025,
            "{}数据源缺少有效的数据库产品类型，请完善数据源配置");
    ErrorCode DATA_ACCESS_TYPE_MAPPING_UNSUPPORTED = new ErrorCode(1_500_013_026, "暂不支持从 {} 到 {} 的类型映射预览");
    ErrorCode FULL_SYNC_WIZARD_NOT_EXISTS = new ErrorCode(1_500_013_027, "整库同步向导不存在");
    ErrorCode FULL_SYNC_WIZARD_STEP_INVALID = new ErrorCode(1_500_013_028, "整库同步向导步骤校验失败：{}");
    ErrorCode FULL_SYNC_WIZARD_REVISION_CONFLICT = new ErrorCode(1_500_013_029, "整库同步向导已被其他人修改，请刷新后重试");
    ErrorCode FULL_SYNC_WIZARD_STATE_INVALID = new ErrorCode(1_500_013_030, "整库同步向导当前状态不允许该操作");
    ErrorCode FULL_SYNC_WIZARD_EXECUTION_MODE_INVALID = new ErrorCode(1_500_013_031, "整库同步执行模式不支持：{}");
    ErrorCode FULL_SYNC_WIZARD_DS_NOT_CONFIGURED = new ErrorCode(1_500_013_032, "DolphinScheduler DataX 模式未配置完整：{}");
    ErrorCode FULL_SYNC_WIZARD_DS_SUBMIT_FAILED = new ErrorCode(1_500_013_033, "DolphinScheduler DataX 提交失败：{}");

    // ========== 数据同步执行记录 1_500_014_000 ==========
    ErrorCode DATA_ACCESS_TASK_NOT_EXISTS = new ErrorCode(1_500_014_000, "数据同步执行记录不存在");

    // ========== 数据填报 1_500_015_000 ==========
    ErrorCode FILL_TABLE_NOT_EXISTS = new ErrorCode(1_500_015_000, "填报表不存在");
    ErrorCode FILL_TABLE_ENG_NAME_INVALID = new ErrorCode(1_500_015_001,
            "表英文名必须以字母开头，只能包含小写字母、数字和下划线");
    ErrorCode FILL_TABLE_ENG_NAME_DUPLICATE = new ErrorCode(1_500_015_002, "已存在同名的填报表");
    ErrorCode FILL_TABLE_RELEASED_CANNOT_MODIFY = new ErrorCode(1_500_015_003, "填报表已发布，请先取消发布");
    ErrorCode FILL_TABLE_NOT_RELEASED = new ErrorCode(1_500_015_004, "填报表尚未发布");
    ErrorCode FILL_TABLE_NO_COLUMN = new ErrorCode(1_500_015_005, "填报表没有配置字段，请先添加字段再发布");
    ErrorCode FILL_TABLE_DATABASE_NOT_EXISTS = new ErrorCode(1_500_015_006, "填报表绑定的目标数据源不存在");
    ErrorCode FILL_TABLE_NO_PK = new ErrorCode(1_500_015_007, "填报表没有主键，无法修改或删除数据");
    ErrorCode FILL_TABLE_DATA_TYPE_ERROR = new ErrorCode(1_500_015_008, "数据格式有误：{}");
    ErrorCode FILL_TABLE_COLUMN_NOT_EXISTS = new ErrorCode(1_500_015_009, "填报表字段不存在");
    ErrorCode FILL_TABLE_COLUMN_NAME_INVALID = new ErrorCode(1_500_015_010,
            "字段名必须以字母开头，只能包含小写字母、数字和下划线，且不能使用系统保留名（source_org_id/period_key/source_submission_id/source_row_id）");
    ErrorCode FILL_TABLE_COLUMN_NAME_DUPLICATE = new ErrorCode(1_500_015_011, "该表下已存在同名字段");
    ErrorCode FILL_TABLE_QUERY_COLUMN_INVALID = new ErrorCode(1_500_015_012, "查询条件字段【{}】不在表字段配置中");
    ErrorCode FILL_TABLE_IMPORT_FILE_INVALID = new ErrorCode(1_500_015_013, "导入文件不合法：{}");
    ErrorCode FILL_TABLE_IMPORT_DATA_INVALID = new ErrorCode(1_500_015_014, "导入数据校验失败：{}");

    // ========== 文件管理 1_500_016_000 ==========

    // ========== 接口请求与采集 1_500_017_000 ==========
    ErrorCode DATA_API_REQUEST_FAILED = new ErrorCode(1_500_017_002, "API 请求失败：{}");
    ErrorCode DATA_API_PARSE_FAILED = new ErrorCode(1_500_017_003, "API 响应解析失败：{}");
    ErrorCode DATA_API_ACCESS_MAPPING_EMPTY = new ErrorCode(1_500_017_005,
            "API 接入任务必须配置至少一个 API 与目标表的映射");
    ErrorCode DATA_API_ACCESS_MAPPING_INVALID = new ErrorCode(1_500_017_006, "API 映射配置不完整：{}");

    // ========== 可视化 ETL 1_500_018_000 ==========
    ErrorCode ETL_PIPELINE_NOT_EXISTS = new ErrorCode(1_500_018_000, "ETL 流程不存在");
    ErrorCode ETL_PIPELINE_NAME_DUPLICATE = new ErrorCode(1_500_018_001, "已存在同名流程");
    ErrorCode ETL_PIPELINE_GRAPH_INVALID = new ErrorCode(1_500_018_002, "流程校验失败：{}");
    ErrorCode ETL_PIPELINE_RELEASED_CANNOT_MODIFY = new ErrorCode(1_500_018_003, "流程已发布，请先取消发布");
    ErrorCode ETL_PIPELINE_NOT_RELEASED = new ErrorCode(1_500_018_004, "流程尚未发布");
    ErrorCode ETL_PIPELINE_CRON_INVALID = new ErrorCode(1_500_018_005, "Cron 表达式不合法");
    ErrorCode ETL_PIPELINE_RUNNING = new ErrorCode(1_500_018_006, "流程正在执行中，请稍后再试");
    ErrorCode ETL_RECORD_NOT_EXISTS = new ErrorCode(1_500_018_007, "ETL 执行记录不存在");
    ErrorCode ETL_RECORD_NOT_RUNNING = new ErrorCode(1_500_018_008, "该执行记录不在运行中");
    ErrorCode ETL_TOOL_NOT_EXISTS = new ErrorCode(1_500_018_009, "工具箱组件不存在");
    ErrorCode ETL_PIPELINE_SCHEDULE_ON = new ErrorCode(1_500_018_010, "调度上线中，请先下线调度");
    ErrorCode ETL_PIPELINE_NOT_RELEASED_FOR_SCHEDULE = new ErrorCode(1_500_018_011, "任务尚未上线，请先上线任务");
    ErrorCode ETL_PIPELINE_NOT_PERIODIC = new ErrorCode(1_500_018_012, "一次性任务无需开启调度");
    ErrorCode ETL_RUN_REQUEST_CONFLICT = new ErrorCode(1_500_018_013, "该请求标识已被其他运行命令使用或对应记录已删除");
    ErrorCode ETL_RUN_PENDING_LIMIT = new ErrorCode(1_500_018_014, "该流程的等待队列已达上限，请等待已有任务完成");
    ErrorCode ETL_RUN_DEFINITION_CHANGED = new ErrorCode(1_500_018_015, "受理前流程或调度配置已变化，请重新加载");
    ErrorCode ETL_RUN_LEASE_LOST = new ErrorCode(1_500_018_016, "执行租约已失效，拒绝保存迟到结果");
    ErrorCode ETL_RUN_VERSION_CONFLICT = new ErrorCode(1_500_018_017, "运行状态已变化，请重新加载后核查");
    ErrorCode ETL_RUN_REVIEW_REQUIRED = new ErrorCode(1_500_018_018,
            "必须核实执行退出和输出结果，且当前运行不可处于有效执行或对账租约内");
    ErrorCode ETL_QUALITY_EVIDENCE_RETAINED = new ErrorCode(1_500_018_019, "存在质量证据，需要完成受控归档后才能删除");
    ErrorCode ETL_QUALITY_REGISTRATION_CHANGED = new ErrorCode(1_500_018_020,
            "质量登记状态已变化，仅失败批次允许人工重试");
    ErrorCode ETL_QUALITY_EVIDENCE_RESTRICTED = new ErrorCode(1_500_018_021,
            "原来源无法安全复核或已发生变化，当前只开放问题原因和统计");
    ErrorCode ETL_VERSION_NOT_EXISTS = new ErrorCode(1_500_018_022, "ETL 历史版本不存在");
    ErrorCode ETL_VERSION_RESTORE_INVALID = new ErrorCode(1_500_018_023, "历史版本内容不完整或不是可恢复的发布版本");
    ErrorCode ETL_TEMPLATE_NOT_EXISTS = new ErrorCode(1_500_018_040, "ETL 模板不存在");
    ErrorCode ETL_TEMPLATE_INVALID = new ErrorCode(1_500_018_041, "ETL 模板不可共享：{}");
    ErrorCode ETL_TEMPLATE_NOT_OWNER = new ErrorCode(1_500_018_042, "只有模板创建人可以删除该模板");
    ErrorCode ETL_TEMPLATE_REQUEST_CONFLICT = new ErrorCode(1_500_018_043, "模板创建请求标识已使用，请刷新核对已有模板");
    ErrorCode ETL_PREVIEW_NOT_FOUND = new ErrorCode(1_500_018_050,
            "当前实例没有可访问的预览会话；可能已重启、过期或路由至其他实例，不能据此确认资源退出");
    ErrorCode ETL_PREVIEW_CAPACITY = new ErrorCode(1_500_018_051,
            "预览会话或样本预算已满，请等待已有会话释放资源或过期");
    ErrorCode ETL_PREVIEW_REQUEST_CONFLICT = new ErrorCode(1_500_018_052,
            "预览请求编号内容不一致或受理窗口已过期，请先核查原会话");
    ErrorCode ETL_PREVIEW_RESULT_RESTRICTED = new ErrorCode(1_500_018_053,
            "当前权限或脱敏结果无法复核，预览缓存已清除，请重新预览");
    ErrorCode ETL_TARGET_CONFIG_INVALID = new ErrorCode(1_500_018_024, "受管目标配置无效：{}");
    ErrorCode ETL_TARGET_BATCH_NOT_EXISTS = new ErrorCode(1_500_018_025, "目标批次不存在");
    ErrorCode ETL_TARGET_STATE_CHANGED = new ErrorCode(1_500_018_026, "目标批次状态已变化，请重新读取");
    ErrorCode ETL_TARGET_NOT_AVAILABLE = new ErrorCode(1_500_018_027, "目标批次尚未完成提交和质量准入");
    ErrorCode ETL_TARGET_INTEGRITY_FAILED = new ErrorCode(1_500_018_028, "目标批次配置、载荷或回执不一致，需核查");
    ErrorCode ETL_FILE_STORAGE_DENIED = new ErrorCode(1_500_018_060,
            "文件输出须使用已配置的 etl://存储键；历史物理路径必须先迁移配置");
    ErrorCode ETL_FILE_NOT_OWNER = new ErrorCode(1_500_018_061, "文件输出不存在或不属于当前执行人");
    ErrorCode ETL_FILE_STATE_CHANGED = new ErrorCode(1_500_018_062, "文件输出身份、状态或修订已变化，请重新读取");
    ErrorCode ETL_FILE_RETRY_DENIED = new ErrorCode(1_500_018_063, "原运行仍有效或文件无需人工重试，不能执行恢复");

    // ========== 数据开发 1_500_020_000 ==========
    ErrorCode DEV_DIR_NOT_EXISTS = new ErrorCode(1_500_020_000, "开发目录不存在");
    ErrorCode DEV_DIR_HAS_CHILDREN = new ErrorCode(1_500_020_001, "存在子目录，无法删除");
    ErrorCode DEV_DIR_HAS_TASK = new ErrorCode(1_500_020_002, "目录下存在开发任务，无法删除");
    ErrorCode DEV_DIR_PARENT_ERROR = new ErrorCode(1_500_020_003, "不能选择自己或子目录作为父目录");
    ErrorCode DEV_TASK_NOT_EXISTS = new ErrorCode(1_500_020_004, "开发任务不存在");
    ErrorCode DEV_TASK_NAME_DUPLICATE = new ErrorCode(1_500_020_005, "已存在同名开发任务");
    ErrorCode DEV_TASK_NOT_ONLINE = new ErrorCode(1_500_020_006, "任务未上线，请先上线任务");
    ErrorCode DEV_TASK_SCHEDULE_ON = new ErrorCode(1_500_020_007, "定时调度开启中，请先关闭定时");
    ErrorCode DEV_TASK_RUNNING = new ErrorCode(1_500_020_008, "任务正在运行中，请稍后再试");
    ErrorCode DEV_TASK_CRON_INVALID = new ErrorCode(1_500_020_009, "Cron 表达式不合法");
    ErrorCode DEV_TASK_CRON_EMPTY = new ErrorCode(1_500_020_010, "请先在调度管理中配置 Cron 表达式");
    ErrorCode DEV_TASK_CONTENT_EMPTY = new ErrorCode(1_500_020_011, "任务脚本内容为空");
    ErrorCode DEV_TASK_DATABASE_EMPTY = new ErrorCode(1_500_020_012, "请先选择数据源");
    ErrorCode DEV_VERSION_NOT_EXISTS = new ErrorCode(1_500_020_013, "任务版本不存在");
    ErrorCode DEV_RECORD_NOT_EXISTS = new ErrorCode(1_500_020_014, "运行实例不存在");
    ErrorCode DEV_RECORD_NOT_RUNNING = new ErrorCode(1_500_020_015, "该运行实例不在运行中");
    ErrorCode DEV_TASK_ONLINE_CANNOT_MODIFY = new ErrorCode(1_500_020_017, "任务已上线，请先下线后再操作");
    ErrorCode DEV_TASK_TYPE_NOT_SUPPORTED = new ErrorCode(1_500_020_018, "不支持的任务类型：{}");
    ErrorCode DEV_DS_DATASOURCE_EMPTY = new ErrorCode(1_500_020_019,
            "集群任务需要配置 DolphinScheduler 数据源编号与类型");
    ErrorCode DEV_DS_SUBMIT_FAILED = new ErrorCode(1_500_020_020, "提交 DolphinScheduler 失败：{}");
    ErrorCode DEV_DS_NOT_CONFIGURED = new ErrorCode(1_500_020_021,
            "尚未配置 DolphinScheduler（dolphinscheduler.server-url / dataforge.dev.ds-project-code）");
    ErrorCode DEV_TASK_REVISION_CONFLICT = new ErrorCode(1_500_020_023, "草稿已被其他人修改，请刷新后重试");
    ErrorCode DEV_TASK_DEPENDENCY_INVALID = new ErrorCode(1_500_020_024, "任务依赖无效：{}");
    ErrorCode DEV_EXECUTION_IDENTITY_REQUIRED = new ErrorCode(1_500_020_025, "缺少可信执行身份");
    ErrorCode DEV_TASK_SAVE_REQUIRED = new ErrorCode(1_500_020_026, "请先保存完整的可执行任务");
    ErrorCode DEV_RUN_REQUEST_CONFLICT = new ErrorCode(1_500_020_027, "请求标识已用于其他执行参数");
    ErrorCode DEV_TASK_SNAPSHOT_INVALID = new ErrorCode(1_500_020_029, "任务版本快照无效：{}");
    ErrorCode DEV_QUALITY_ACCESS_DENIED = new ErrorCode(1_500_020_030, "执行身份没有质量任务的使用权限");
    ErrorCode DEV_PROGRAM_CONFIG_INVALID = new ErrorCode(1_500_020_031, "任务程序配置无效：{}");
    ErrorCode DEV_NATIVE_RUNTIME_NOT_READY = new ErrorCode(1_500_020_032,
            "该任务的原生引擎执行尚未接通，可先保存草稿；不能通过普通 SQL 通道执行");

    // ========== 在线填报 1_500_021_000 ==========
    // ----- 模板版本 -----
    ErrorCode FILL_TABLE_VERSION_NOT_EXISTS = new ErrorCode(1_500_021_000, "模板版本不存在");
    ErrorCode FILL_TABLE_VERSION_DISABLED = new ErrorCode(1_500_021_001, "模板版本已停用，不能被新任务引用");
    ErrorCode FILL_TABLE_FIELD_CODE_MISSING = new ErrorCode(1_500_021_002, "字段【{}】缺少字段码，请先保存字段配置");
    ErrorCode FILL_MERGE_STRATEGY_INVALID = new ErrorCode(1_500_021_003, "合并策略不合法：{}");
    ErrorCode FILL_BUSINESS_KEY_REQUIRED = new ErrorCode(1_500_021_004, "UPSERT 合并策略必须配置业务键字段");
    ErrorCode FILL_BUSINESS_KEY_FIELD_INVALID = new ErrorCode(1_500_021_005, "业务键字段【{}】不在模板字段中");
    // ----- 采集计划 -----
    ErrorCode FILL_PLAN_NOT_EXISTS = new ErrorCode(1_500_021_010, "采集计划不存在");
    ErrorCode FILL_PLAN_STATUS_INVALID = new ErrorCode(1_500_021_011, "采集计划当前状态不允许该操作");
    ErrorCode FILL_PLAN_NO_ORG = new ErrorCode(1_500_021_012, "采集计划未配置目标组织");
    ErrorCode FILL_PLAN_ORG_NO_ASSIGNEE = new ErrorCode(1_500_021_013, "组织【{}】未配置填报人，不允许发布");
    ErrorCode FILL_PLAN_TARGET_SCOPE_CONFLICT = new ErrorCode(1_500_021_014, "目标表写入范围冲突：{}");
    // ----- 周期任务 -----
    ErrorCode FILL_TASK_NOT_EXISTS = new ErrorCode(1_500_021_020, "采集任务不存在");
    ErrorCode FILL_TASK_PERIOD_DUPLICATE = new ErrorCode(1_500_021_021, "该计划在所属期【{}】的任务已存在");
    ErrorCode FILL_TASK_STATUS_INVALID = new ErrorCode(1_500_021_022, "采集任务当前状态不允许该操作");
    // ----- 工作项 -----
    ErrorCode FILL_WORK_ITEM_NOT_EXISTS = new ErrorCode(1_500_021_030, "组织填报实例不存在");
    ErrorCode FILL_WORK_ITEM_STATUS_INVALID = new ErrorCode(1_500_021_031, "填报实例当前状态【{}】不允许该操作");
    ErrorCode FILL_WORK_ITEM_NOT_ASSIGNEE = new ErrorCode(1_500_021_032, "您不是该填报实例的有效填报人");
    ErrorCode FILL_WORK_ITEM_VERSION_CONFLICT = new ErrorCode(1_500_021_033, "数据版本已变化，请刷新后重试");
    ErrorCode FILL_WORK_ITEM_SUBMITTED_BY_OTHER = new ErrorCode(1_500_021_034, "数据已由【{}】提交，当前为只读状态");
    ErrorCode FILL_WORK_ITEM_OVERDUE_LOCKED = new ErrorCode(1_500_021_035,
            "已超过截止时间且任务为硬截止，请联系发起者延期");
    // ----- 填报人 -----
    ErrorCode FILL_ASSIGNEE_LAST_ONE = new ErrorCode(1_500_021_040, "填报实例必须至少保留一名有效填报人");
    ErrorCode FILL_ASSIGNEE_ALREADY_EXISTS = new ErrorCode(1_500_021_041, "该用户已是本实例的有效填报人");
    ErrorCode FILL_ASSIGNEE_TRANSFER_TARGET_INVALID = new ErrorCode(1_500_021_042,
            "转派目标人员不合法（需同组织内有效账号）");
    ErrorCode FILL_DEPT_VIEW_NOT_LEADER = new ErrorCode(1_500_021_043, "仅部门负责人可查看本部门填报数据");
    // ----- 草稿与操作 -----
    ErrorCode FILL_DRAFT_ROW_NOT_EXISTS = new ErrorCode(1_500_021_050, "草稿行不存在");
    ErrorCode FILL_DRAFT_ROW_VERSION_CONFLICT = new ErrorCode(1_500_021_051, "该单元格已被【{}】修改，请刷新后重试");
    ErrorCode FILL_OPERATION_DUPLICATE = new ErrorCode(1_500_021_052, "操作已执行（重复的客户端操作号）");
    ErrorCode FILL_FIELD_NOT_EDITABLE = new ErrorCode(1_500_021_053, "字段【{}】为公式/系统列，不允许编辑");
    ErrorCode FILL_OPERATION_INVALID = new ErrorCode(1_500_021_054, "操作不合法：{}");
    // ----- 提交与审批 -----
    ErrorCode FILL_SUBMISSION_NOT_EXISTS = new ErrorCode(1_500_021_060, "提交快照不存在");
    ErrorCode FILL_SUBMISSION_VALIDATE_FAILED = new ErrorCode(1_500_021_061, "提交校验未通过，共 {} 个错误");
    ErrorCode FILL_SUBMISSION_NOT_SUBMITTER = new ErrorCode(1_500_021_062, "只有本次实际提交人才能执行该操作");
    // ----- 合并 -----
    ErrorCode FILL_MERGE_BATCH_NOT_EXISTS = new ErrorCode(1_500_021_070, "合并批次不存在");
    ErrorCode FILL_MERGE_BATCH_STATUS_INVALID = new ErrorCode(1_500_021_071, "合并批次当前状态不允许该操作");
    ErrorCode FILL_MERGE_TARGET_INVALID = new ErrorCode(1_500_021_072, "目标表结构校验失败：{}");
    // ----- 事件 -----
    ErrorCode FILL_EVENT_DUPLICATE = new ErrorCode(1_500_021_080, "事件已接收，重复投递被忽略");
    // ----- Excel 导入导出 -----
    ErrorCode FILL_EXCEL_FILE_INVALID = new ErrorCode(1_500_021_090, "Excel 文件不合法：{}");
    ErrorCode FILL_EXCEL_ROW_LIMIT_EXCEEDED = new ErrorCode(1_500_021_091, "导入行数 {} 超过单次上限 {}");
    ErrorCode FILL_EXCEL_NO_MATCHED_COLUMN = new ErrorCode(1_500_021_092,
            "表头没有匹配到任何模板输入列，请使用系统下载的模板");
    ErrorCode FILL_EXCEL_GENERATE_FAILED = new ErrorCode(1_500_021_093, "Excel 生成失败：{}");
    // ----- 数据更正 -----
    ErrorCode FILL_CORRECTION_NOT_EXISTS = new ErrorCode(1_500_021_100, "数据更正实例不存在");
    ErrorCode FILL_CORRECTION_ONLY_MERGED = new ErrorCode(1_500_021_101, "仅已合并的组织实例可发起数据更正");
    ErrorCode FILL_CORRECTION_NOT_LATEST = new ErrorCode(1_500_021_102, "只能对最新一次已合并的提交快照发起更正");
    ErrorCode FILL_CORRECTION_APPEND_UNSUPPORTED = new ErrorCode(1_500_021_103,
            "APPEND 合并策略的模板不支持更正（会产生重复记录），请走人工数据处理流程");
    ErrorCode FILL_CORRECTION_EXISTS_ACTIVE = new ErrorCode(1_500_021_104, "该组织实例已存在进行中的更正，不能重复发起");
    ErrorCode FILL_CORRECTION_STATUS_INVALID = new ErrorCode(1_500_021_105, "更正实例当前状态不允许该操作");
    // ----- 催办 -----
    ErrorCode FILL_URGE_TOO_FREQUENT = new ErrorCode(1_500_021_110, "该组织 {} 分钟内已催办过，请稍后再试");

    // ----- 采集计划分类 -----
    ErrorCode FILL_PLAN_CATEGORY_NOT_EXISTS = new ErrorCode(1_500_021_120, "采集计划分类不存在");
    ErrorCode FILL_PLAN_CATEGORY_PARENT_NOT_EXISTS = new ErrorCode(1_500_021_121, "父分类不存在");
    ErrorCode FILL_PLAN_CATEGORY_PARENT_INVALID = new ErrorCode(1_500_021_122, "不能将自己或子孙分类设置为父分类");
    ErrorCode FILL_PLAN_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_021_123, "同级目录下已存在同名分类");
    ErrorCode FILL_PLAN_CATEGORY_HAS_CHILDREN = new ErrorCode(1_500_021_124, "分类下存在子分类，不允许删除");
    ErrorCode FILL_PLAN_CATEGORY_HAS_PLANS = new ErrorCode(1_500_021_125, "分类下存在采集计划，请先调整计划分类");

    // ========== 数据推送 1_500_025_000 ==========
    ErrorCode DATA_PUSH_CONFIG_NOT_EXISTS = new ErrorCode(1_500_025_000, "推送配置不存在");
    ErrorCode DATA_PUSH_TASK_NOT_EXISTS = new ErrorCode(1_500_025_001, "推送任务不存在");
    ErrorCode DATA_PUSH_LOG_NOT_EXISTS = new ErrorCode(1_500_025_002, "推送执行记录不存在");
    ErrorCode DATA_PUSH_RELEASED_CANNOT_MODIFY = new ErrorCode(1_500_025_003, "任务已发布，请先取消发布");
    ErrorCode DATA_PUSH_NOT_RELEASED = new ErrorCode(1_500_025_004, "任务尚未发布");
    ErrorCode DATA_PUSH_CHANNEL_TYPE_INVALID = new ErrorCode(1_500_025_005, "不支持的推送通道类型");
    ErrorCode DATA_PUSH_CHANNEL_CONFIG_INVALID = new ErrorCode(1_500_025_006, "推送通道配置不完整：{}");
    ErrorCode DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS = new ErrorCode(1_500_025_007, "源端数据源不存在");
    ErrorCode DATA_PUSH_TARGET_DATABASE_NOT_EXISTS = new ErrorCode(1_500_025_008, "目标数据源不存在");
    ErrorCode DATA_PUSH_SQL_INVALID = new ErrorCode(1_500_025_009, "SQL 不合法：{}");
    ErrorCode DATA_PUSH_INCREMENT_COLUMN_REQUIRED = new ErrorCode(1_500_025_010, "增量推送必须指定增量字段");
    ErrorCode DATA_PUSH_INCREMENT_COLUMN_NOT_EXISTS = new ErrorCode(1_500_025_011, "增量字段【{}】不存在");
    ErrorCode DATA_PUSH_TABLE_REQUIRED = new ErrorCode(1_500_025_012, "表模式必须选择源表");
    ErrorCode DATA_PUSH_CRON_INVALID = new ErrorCode(1_500_025_013,
            "cron 表达式无效（需为 6 位 Spring cron，例如 0 0 2 * * ?）：{}");
    ErrorCode DATA_PUSH_RUNNING = new ErrorCode(1_500_025_014,
            "任务尚在执行或存在未确认结果，请等待结束或核查，禁止重复推送");
    ErrorCode DATA_PUSH_NOT_RUNNING = new ErrorCode(1_500_025_015, "任务当前没有在运行");
    ErrorCode DATA_PUSH_CONFIG_IN_USE = new ErrorCode(1_500_025_016, "推送配置已被任务引用，不能删除");
    ErrorCode DATA_PUSH_MODE_INVALID = new ErrorCode(1_500_025_017, "推送方式仅支持全量或增量");
    ErrorCode DATA_PUSH_TASK_TYPE_INVALID = new ErrorCode(1_500_025_018, "调度类型仅支持一次性或周期性");
    ErrorCode DATA_PUSH_TYPE_INVALID = new ErrorCode(1_500_025_019, "推送类型仅支持数据表或自定义 SQL");
    ErrorCode DATA_PUSH_CONFIG_LOCKED = new ErrorCode(1_500_025_020, "推送配置已被已发布任务引用，请先取消发布相关任务");
    ErrorCode DATA_PUSH_TABLE_NOT_EXISTS = new ErrorCode(1_500_025_021, "源表【{}】不存在或无法访问");
    ErrorCode DATA_PUSH_INCREMENT_COLUMN_TYPE_INVALID = new ErrorCode(1_500_025_022,
            "增量字段【{}】类型不支持（{}），请选择数值或时间类型");
    ErrorCode DATA_PUSH_EXECUTOR_BUSY = new ErrorCode(1_500_025_023, "推送执行队列已满，请稍后重试");
    ErrorCode DATA_PUSH_INCREMENT_NOT_ENABLED = new ErrorCode(1_500_025_024, "该任务不是增量推送，没有水位");
    ErrorCode DATA_PUSH_SOURCE_META_FAILED = new ErrorCode(1_500_025_025, "读取源结构失败：{}");
    ErrorCode DATA_PUSH_INIT_WATERMARK_INVALID = new ErrorCode(1_500_025_026, "首次起始水位不合法：{}");
    ErrorCode DATA_PUSH_PREVIEW_FAILED = new ErrorCode(1_500_025_027, "试跑预览失败：{}");
    ErrorCode DATA_PUSH_CHANNEL_TEST_FAILED = new ErrorCode(1_500_025_030, "推送通道测试失败：{}");
    ErrorCode DATA_PUSH_REVIEW_REQUIRED = new ErrorCode(1_500_025_031,
            "仅可核查未知记录；须确认工作者及外部请求终止、目标无生效或已完整补偿，活跃领取未结束时禁止处置");
    ErrorCode DATA_PUSH_REVIEW_CONFLICT = new ErrorCode(1_500_025_032,
            "核查记录或请求已变化，请刷新；同一请求编号不能更换内容或操作者");

    ErrorCode DATA_PUSH_CATEGORY_INVALID = new ErrorCode(1_500_025_033, "推送类目操作失败：{}");

    // ========== 批量文件入库 1_500_029_000 ==========
    ErrorCode BATCH_FILE_NOT_EXISTS = new ErrorCode(1_500_029_000, "批量文件入库任务不存在");
    ErrorCode BATCH_FILE_CONFIG_INVALID = new ErrorCode(1_500_029_001, "入库配置不合法：{}");
    ErrorCode BATCH_FILE_UPLOAD_INVALID = new ErrorCode(1_500_029_002, "文件不可用、已过期或不属于当前用户");
    ErrorCode BATCH_FILE_UPLOAD_FAILED = new ErrorCode(1_500_029_003, "文件上传或解析失败，请检查文件格式与大小后重试");
    ErrorCode BATCH_FILE_STATE_INVALID = new ErrorCode(1_500_029_004, "任务状态不允许操作，请先下线或等待运行结束");
    ErrorCode BATCH_FILE_CONFLICT = new ErrorCode(1_500_029_005, "任务已被修改、名称重复或请求编号冲突，请刷新后重试");
    ErrorCode BATCH_FILE_TARGET_INVALID = new ErrorCode(1_500_029_006, "目标数据源必须是已启用的 MySQL 数据源");
    ErrorCode BATCH_FILE_RUN_NOT_EXISTS = new ErrorCode(1_500_029_007, "入库运行实例不存在");
    ErrorCode BATCH_FILE_REVIEW_REQUIRED = new ErrorCode(1_500_029_008,
            "请先停止外部写入并核查目标数据；活跃工作者未结束时不可解除未知结果");
    ErrorCode BATCH_FILE_STORAGE_UNAVAILABLE = new ErrorCode(1_500_029_009, "受管文件服务暂不可用，请稍后重试");
}
