package cn.iocoder.yudao.module.governance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 *  错误码 Core 枚举类
 *
 * 数据安全，使用 1_600_000_000 段
 * 数据标准，使用 1_700_000_000 段
 */
public interface ErrorCodeConstants {

    // ========== 数据分类维护 1_900_010_000 ==========
    ErrorCode SAFETY_CATEGORY_CONFLICT = new ErrorCode(1_900_010_000, "记录已被其他操作修改，请刷新后重试");
    ErrorCode SAFETY_CATEGORY_INVALID = new ErrorCode(1_900_010_001, "数据分类参数不合法：{}");
    ErrorCode SAFETY_CATEGORY_DUPLICATE = new ErrorCode(1_900_010_002, "该类目下已存在同名数据分类");
    ErrorCode SAFETY_CATEGORY_REFERENCED = new ErrorCode(1_900_010_003, "数据分类仍被治理策略引用，请先解除引用");
    ErrorCode SAFETY_DIRECTORY_REFERENCED = new ErrorCode(1_900_010_004, "类目仍包含数据分类，无法删除");
    ErrorCode SAFETY_MASK_INVALID = new ErrorCode(1_900_010_005, "展示脱敏配置不合法：{}");


    // ========== 数据安全--数据等级分级信息 ==========
    ErrorCode LEVEL_NOT_EXISTS = new ErrorCode(1_600_000_100, "数据分级信息不存在");
    ErrorCode LEVEL_CODE_DUPLICATE = new ErrorCode(1_600_000_101, "安全等级【{}】已存在");
    // ========== 数据安全--数据分类信息 ==========
    ErrorCode CATEGORY_NOT_EXISTS = new ErrorCode(1_600_000_200, "数据分类信息不存在");
    ErrorCode CATEGORY_DELETE_FAIL_STATUS_ENABLE = new ErrorCode(1_600_000_201,
            "数据分类【{}】处于启用状态，请先停用后再删除");

    // ========== 数据安全--脱敏白名单  ==========
    ErrorCode WHITELIST_NOT_EXISTS = new ErrorCode(1_600_000_300, "脱敏白名单不存在");
    ErrorCode WHITELIST_APPROVAL_STATUS_INVALID = new ErrorCode(1_600_000_301, "白名单当前审批状态不允许该操作");
    ErrorCode WHITELIST_PENDING_READONLY = new ErrorCode(1_600_000_302, "审批中的白名单不允许修改");
    ErrorCode WHITELIST_TIME_RANGE_INVALID = new ErrorCode(1_600_000_303, "生效开始时间不能晚于结束时间");
    // ========== 数据安全--脱敏规则（主表）；参数演进由版本表承载 ==========
    ErrorCode RULE_NOT_EXISTS = new ErrorCode(1_600_000_400, "脱敏规则不存在");
    ErrorCode RULE_BUILTIN_CANNOT_DELETE = new ErrorCode(1_600_000_402, "内置规则【{}】不允许删除");
    ErrorCode RULE_METHOD_CODE_CANNOT_CHANGE = new ErrorCode(1_600_000_403, "脱敏方式不允许变更，请新建规则");
    ErrorCode RULE_CONFIG_INVALID = new ErrorCode(1_600_000_404, "脱敏参数不合法：{}");
    ErrorCode RULE_VER_NOT_EXISTS = new ErrorCode(1_600_000_405, "脱敏规则版本不存在");
    ErrorCode RULE_VERSION_UNAVAILABLE = new ErrorCode(1_600_000_406,
            "脱敏规则不存在、已停用或没有已发布版本，拒绝按明文回退");
    ErrorCode RULE_REFERENCED = new ErrorCode(1_600_000_407,
            "脱敏规则仍被字段权限或静态脱敏任务引用，请先替换后再删除或停用");

    // ========== 数据安全--静态脱敏任务 1_600_000_500 ==========
    ErrorCode STATIC_TASK_NOT_EXISTS = new ErrorCode(1_600_000_500, "静态脱敏任务不存在");
    ErrorCode STATIC_TASK_NAME_DUPLICATE = new ErrorCode(1_600_000_501, "已存在同名的静态脱敏任务");
    ErrorCode STATIC_TASK_RUNNING = new ErrorCode(1_600_000_502, "静态脱敏任务正在执行中，请稍后再试");
    ErrorCode STATIC_TASK_CRON_INVALID = new ErrorCode(1_600_000_503, "Cron 表达式不合法");
    ErrorCode STATIC_TASK_CONFIG_INVALID = new ErrorCode(1_600_000_504, "静态脱敏任务配置不合法：{}");
    ErrorCode STATIC_TASK_RULE_NOT_AVAILABLE = new ErrorCode(1_600_000_505, "脱敏规则({})不存在或未发布生效版本");
    ErrorCode STATIC_TASK_DATABASE_NOT_EXISTS = new ErrorCode(1_600_000_506, "静态脱敏任务关联的数据源不存在");
    ErrorCode MASK_METHOD_NOT_SUPPORTED = new ErrorCode(1_600_000_507, "脱敏方式【{}】暂不支持执行");

    // ========== 数据安全--敏感识别与字段打标 1_600_000_600 ==========
    ErrorCode IDENTIFY_RULE_NOT_EXISTS = new ErrorCode(1_600_000_600, "敏感识别规则不存在");
    ErrorCode IDENTIFY_RULE_NAME_DUPLICATE = new ErrorCode(1_600_000_601, "已存在同名的敏感识别规则");
    ErrorCode IDENTIFY_RULE_PATTERN_INVALID = new ErrorCode(1_600_000_602, "匹配模式不合法：{}");
    ErrorCode IDENTIFY_RULE_BUILTIN_CANNOT_DELETE = new ErrorCode(1_600_000_603, "内置识别规则不允许删除");
    ErrorCode SCAN_RUN_NOT_EXISTS = new ErrorCode(1_600_000_610, "扫描记录不存在");
    ErrorCode SCAN_RUNNING = new ErrorCode(1_600_000_611, "该数据源存在进行中的扫描，请稍后再试");
    ErrorCode SCAN_NO_ENABLED_RULE = new ErrorCode(1_600_000_612, "没有启用中的敏感识别规则，无法扫描");
    ErrorCode FIELD_TAG_NOT_EXISTS = new ErrorCode(1_600_000_620, "字段打标记录不存在");
    ErrorCode FIELD_TAG_DUPLICATE = new ErrorCode(1_600_000_621, "该字段已存在打标记录");
    ErrorCode FIELD_TAG_STATUS_INVALID = new ErrorCode(1_600_000_622, "打标记录当前状态不允许该操作");

    // ========== 数据安全--数据权限 1_600_000_700 ==========
    ErrorCode PERMISSION_TABLE_REQUIRED = new ErrorCode(1_600_000_700, "请先选择要配置的数据表");
    ErrorCode PERMISSION_ROLE_REQUIRED = new ErrorCode(1_600_000_701, "请先配置至少一个角色的权限");
    ErrorCode PERMISSION_MASKING_RULE_REQUIRED = new ErrorCode(1_600_000_702,
            "角色【{}】的字段【{}】选择了脱敏访问但未配置脱敏规则");
    ErrorCode PERMISSION_TABLE_DENIED = new ErrorCode(1_600_000_703, "无权限访问表数据：{}");
    ErrorCode PERMISSION_TABLE_WRITE_DENIED = new ErrorCode(1_600_000_704, "无权限操作表数据：{}");
    ErrorCode PERMISSION_FIELD_DENIED = new ErrorCode(1_600_000_705, "字段【{}】无访问权限");
    ErrorCode PERMISSION_SQL_PARSE_FAIL = new ErrorCode(1_600_000_706, "SQL 解析失败：{}");
    ErrorCode PERMISSION_TYPE_INVALID = new ErrorCode(1_600_000_707, "权限类型不合法");
    ErrorCode PERMISSION_ROW_FILTER_INVALID = new ErrorCode(1_600_000_708, "行级条件不合法：{}");
    ErrorCode PERMISSION_MASKING_RULE_UNAVAILABLE = new ErrorCode(1_600_000_709,
            "角色【{}】的字段【{}】引用的脱敏规则不可用");

    // ========== 数据标准--标准文件登记  ==========
    ErrorCode DOC_NOT_EXISTS = new ErrorCode(1_700_000_100, "标准文件信息不存在");
    ErrorCode DOC_REGISTRY_NO_DUPLICATE = new ErrorCode(1_700_000_101, "入库编号已存在");
    ErrorCode DOC_REPLACE_INVALID = new ErrorCode(1_700_000_102, "替代标准关系不合法");
    ErrorCode DOC_REFERENCED = new ErrorCode(1_700_000_103, "标准文件已被引用，无法删除");
    ErrorCode DOC_FILE_NOT_EXISTS = new ErrorCode(1_700_000_104, "标准原文不存在");
    ErrorCode DOC_FILE_URL_INVALID = new ErrorCode(1_700_000_105, "标准原文访问地址仅支持 http/https 协议");

    // ========== 数据标准--标准码 1_800_000_100 ==========
    ErrorCode STANDARD_CODE_SET_NOT_EXISTS = new ErrorCode(1_800_000_100, "数据标准--标准码不存在");
    ErrorCode STANDARD_CODE_SET_REFERENCED = new ErrorCode(1_800_000_101, "标准码表已被标准数据元引用，无法删除");
    ErrorCode STANDARD_CODE_SET_IMPORT_LIST_EMPTY = new ErrorCode(1_800_000_102, "导入码表/码值列表为空");
    ErrorCode STANDARD_CODE_SET_IMPORT_FIELD_MISSING = new ErrorCode(1_800_000_103,
            "字典类型、码值键值、码值标签不能为空");
    ErrorCode STANDARD_CODE_SET_IMPORT_NAME_MISSING = new ErrorCode(1_800_000_104,
            "字典类型({})对应的码表不存在，且未填写码表名称，无法自动创建");
    ErrorCode STANDARD_CODE_SET_IMPORT_ITEM_DUPLICATE = new ErrorCode(1_800_000_105,
            "该字典类型下已存在相同键值的码值，且未开启更新模式");
    ErrorCode STANDARD_CODE_ITEM_NOT_EXISTS = new ErrorCode(1_800_000_200, "数据标准--码值不存在");
    ErrorCode STANDARD_CODE_ITEM_VALUE_DUPLICATE = new ErrorCode(1_800_000_201, "已经存在该键值的码值");
    ErrorCode STANDARD_CODE_ITEM_CODE_SET_REQUIRED = new ErrorCode(1_800_000_202, "codeSetId 与 dictType 至少填写一个");
    ErrorCode STANDARD_CODE_ITEM_CODE_SET_CONFLICT = new ErrorCode(1_800_000_203,
            "codeSetId 与 dictType 指向的码表不一致");


    // ========== 数据标准--标准数据元 1_900_000_100 ==========
    ErrorCode STANDARD_ELEMENT_NOT_EXISTS = new ErrorCode(1_900_000_100, "数据标准--标准数据元不存在");
    ErrorCode STANDARD_ELEMENT_NAME_DUPLICATE = new ErrorCode(1_900_000_101, "已存在相同中文名称的标准数据元");
    ErrorCode STANDARD_ELEMENT_ENG_NAME_DUPLICATE = new ErrorCode(1_900_000_102, "已存在相同英文名称的标准数据元");
    ErrorCode STANDARD_ELEMENT_CATEGORY_NOT_EXISTS = new ErrorCode(1_900_000_103, "数据元类目不存在");
    ErrorCode STANDARD_ELEMENT_REFERENCED = new ErrorCode(1_900_000_104, "标准数据元已被元数据字段引用，无法删除");
    ErrorCode STANDARD_ELEMENT_LIFECYCLE_TRANSITION_INVALID = new ErrorCode(1_900_000_105,
            "标准数据元生命周期状态不允许该操作");
    ErrorCode STANDARD_ELEMENT_PUBLISHED_CANNOT_DELETE = new ErrorCode(1_900_000_106,
            "已发布的标准数据元不能删除，请先废止");
    ErrorCode STANDARD_ELEMENT_DEPRECATED_READONLY = new ErrorCode(1_900_000_107, "已废止的标准数据元不允许修改");
    ErrorCode STANDARD_ELEMENT_IMPORT_LIST_EMPTY = new ErrorCode(1_900_000_108, "导入数据元列表为空");
    ErrorCode STANDARD_ELEMENT_IMPORT_FIELD_MISSING = new ErrorCode(1_900_000_109,
            "中文名称、英文名称、字段类型不能为空");
    ErrorCode STANDARD_ELEMENT_IMPORT_CATEGORY_AMBIGUOUS = new ErrorCode(1_900_000_110,
            "数据元类目名称存在多个匹配，请修改类目名称后重试");
    ErrorCode STANDARD_CONSTRAINT_COMPILE_FAILED = new ErrorCode(1_900_000_113, "标准约束编译失败：{}");
    ErrorCode STANDARD_QUALITY_BINDING_INVALID = new ErrorCode(1_900_000_115, "标准质量执行绑定失败：{}");

    // ========== 数据标准--落标映射 1_900_000_200 ==========
    ErrorCode STANDARD_MAPPING_NOT_EXISTS = new ErrorCode(1_900_000_200, "落标映射不存在");
    ErrorCode STANDARD_MAPPING_STATUS_INVALID = new ErrorCode(1_900_000_201, "落标映射当前状态不允许该操作");
    ErrorCode STANDARD_MAPPING_STANDARD_NOT_PUBLISHED = new ErrorCode(1_900_000_214,
            "落标目标必须是已发布且启用的标准版本");
    ErrorCode STANDARD_MAPPING_COMMAND_CONFLICT = new ErrorCode(1_900_000_215,
            "同一目标已存在其他活动落标，请刷新后重试");

    // ========== 数据标准--业务术语 1_900_000_400 ==========
    ErrorCode STANDARD_TERM_NOT_EXISTS = new ErrorCode(1_900_000_400, "业务术语不存在");
    ErrorCode STANDARD_TERM_NAME_DUPLICATE = new ErrorCode(1_900_000_401, "已存在同名业务术语");

    // ========== 数据分类 类目管理 1_500_006_000 ==========
    ErrorCode CATEGORY_DATA_NOT_EXISTS = new ErrorCode(1_500_006_001, "数据分类 类目管理不存在");
    ErrorCode CATEGORY_DATA_EXITS_CHILDREN = new ErrorCode(1_500_006_002, "存在存在子数据分类 类目管理，无法删除");
    ErrorCode CATEGORY_DATA_PARENT_NOT_EXITS = new ErrorCode(1_500_006_003, "父级数据分类 类目管理不存在");
    ErrorCode CATEGORY_DATA_PARENT_ERROR = new ErrorCode(1_500_006_004, "不能设置自己为父数据分类 类目管理");
    ErrorCode CATEGORY_DATA_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_006_005,
            "已经存在该类目名称的数据分类 类目管理");
    ErrorCode CATEGORY_DATA_PARENT_IS_CHILD = new ErrorCode(1_500_006_006,
            "不能设置自己的子CategoryData为父CategoryData");

    // ========== 清洗规则 类目管理 1_500_007_000 ==========
    ErrorCode CATEGORY_CLEAN_RULE_NOT_EXISTS = new ErrorCode(1_500_007_001, "清洗规则 类目管理不存在");
    ErrorCode CATEGORY_CLEAN_RULE_EXITS_CHILDREN = new ErrorCode(1_500_007_002, "存在存在子清洗规则 类目管理，无法删除");
    ErrorCode CATEGORY_CLEAN_RULE_PARENT_NOT_EXITS = new ErrorCode(1_500_007_003, "父级清洗规则 类目管理不存在");
    ErrorCode CATEGORY_CLEAN_RULE_PARENT_ERROR = new ErrorCode(1_500_007_004, "不能设置自己为父清洗规则 类目管理");
    ErrorCode CATEGORY_CLEAN_RULE_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_007_005,
            "已经存在该类目名称的清洗规则 类目管理");
    ErrorCode CATEGORY_CLEAN_RULE_PARENT_IS_CHILD = new ErrorCode(1_500_007_006,
            "不能设置自己的子CategoryCleanRule为父CategoryCleanRule");

    // ========== 标准数据元类目管理 1_500_008_000 ==========
    ErrorCode CATEGORY_DATAMETA_NOT_EXISTS = new ErrorCode(1_500_008_001, "标准数据元类目管理不存在");
    ErrorCode CATEGORY_DATAMETA_EXITS_CHILDREN = new ErrorCode(1_500_008_002, "存在存在子标准数据元类目管理，无法删除");
    ErrorCode CATEGORY_DATAMETA_PARENT_NOT_EXITS = new ErrorCode(1_500_008_003, "父级标准数据元类目管理不存在");
    ErrorCode CATEGORY_DATAMETA_PARENT_ERROR = new ErrorCode(1_500_008_004, "不能设置自己为父标准数据元类目管理");
    ErrorCode CATEGORY_DATAMETA_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_008_005,
            "已经存在该类目名称的标准数据元类目管理");
    ErrorCode CATEGORY_DATAMETA_PARENT_IS_CHILD = new ErrorCode(1_500_008_006,
            "不能设置自己的子CategoryDatameta为父CategoryDatameta");

    // ========== 业务术语类目管理 1_900_017_000 ==========
    ErrorCode CATEGORY_TERM_NOT_EXISTS = new ErrorCode(1_900_017_001, "业务术语类目不存在");
    ErrorCode CATEGORY_TERM_EXITS_CHILDREN = new ErrorCode(1_900_017_002, "存在子业务术语类目，无法删除");
    ErrorCode CATEGORY_TERM_PARENT_NOT_EXITS = new ErrorCode(1_900_017_003, "父级业务术语类目不存在");
    ErrorCode CATEGORY_TERM_PARENT_ERROR = new ErrorCode(1_900_017_004, "不能设置自己为父业务术语类目");
    ErrorCode CATEGORY_TERM_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_900_017_005,
            "已经存在该类目名称的业务术语类目");
    ErrorCode CATEGORY_TERM_REFERENCED = new ErrorCode(1_900_017_007, "类目下存在业务术语，无法删除");
    ErrorCode CATEGORY_TERM_PARENT_IS_CHILD = new ErrorCode(1_900_017_006,
            "不能设置自己的子业务术语类目为父业务术语类目");

    // ========== 数据血缘 1_900_018_000 ==========
    ErrorCode LINEAGE_NODE_NOT_EXISTS = new ErrorCode(1_900_018_001, "血缘节点不存在");
    ErrorCode LINEAGE_REPORT_INVALID = new ErrorCode(1_900_018_002, "血缘上报内容无效：{}");
    ErrorCode LINEAGE_QUERY_INVALID = new ErrorCode(1_900_018_003, "血缘查询参数无效：{}");

    // ========== 数据质量类目管理 1_500_010_000 ==========
    ErrorCode CATEGORY_DATA_QUALITY_NOT_EXISTS = new ErrorCode(1_500_010_001, "数据质量类目管理不存在");
    ErrorCode CATEGORY_DATA_QUALITY_EXITS_CHILDREN = new ErrorCode(1_500_010_002,
            "存在存在子数据质量类目管理，无法删除");
    ErrorCode CATEGORY_DATA_QUALITY_PARENT_NOT_EXITS = new ErrorCode(1_500_010_003, "父级数据质量类目管理不存在");
    ErrorCode CATEGORY_DATA_QUALITY_PARENT_ERROR = new ErrorCode(1_500_010_004, "不能设置自己为父数据质量类目管理");
    ErrorCode CATEGORY_DATA_QUALITY_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_010_005,
            "已经存在该类目名称的数据质量类目管理");
    ErrorCode CATEGORY_DATA_QUALITY_PARENT_IS_CHILD = new ErrorCode(1_500_010_006,
            "不能设置自己的子CategoryDataQuality为父CategoryDataQuality");

    // ========== 数据质量--稽查规则 1_500_013_000 ==========
    ErrorCode AUDIT_RULE_NOT_EXISTS = new ErrorCode(1_500_013_000, "稽查规则不存在");
    ErrorCode AUDIT_RULE_NAME_DUPLICATE = new ErrorCode(1_500_013_001, "已存在同名的稽查规则");
    ErrorCode AUDIT_RULE_DIMENSION_NOT_EXISTS = new ErrorCode(1_500_013_002, "质量维度不存在");
    ErrorCode AUDIT_RULE_STRATEGY_NOT_SUPPORTED = new ErrorCode(1_500_013_003, "策略标识未接入质量引擎：{}");
    ErrorCode AUDIT_RULE_NO_STRATEGY = new ErrorCode(1_500_013_004, "该规则暂未接入质量引擎，无法生成稽查 SQL");
    ErrorCode AUDIT_RULE_CONFIG_INVALID = new ErrorCode(1_500_013_005, "规则配置不合法：{}");

    // ========== 数据质量--清洗规则 1_500_014_000 ==========
    ErrorCode CLEAN_RULE_NOT_EXISTS = new ErrorCode(1_500_014_000, "清洗规则不存在");
    ErrorCode CLEAN_RULE_NAME_DUPLICATE = new ErrorCode(1_500_014_001, "已存在同名的清洗规则");
    ErrorCode CLEAN_RULE_CATEGORY_NOT_EXISTS = new ErrorCode(1_500_014_002, "清洗规则类目不存在");
    ErrorCode CLEAN_RULE_STRATEGY_NOT_SUPPORTED = new ErrorCode(1_500_014_003, "策略标识未接入质量引擎：{}");
    ErrorCode CLEAN_RULE_NO_STRATEGY = new ErrorCode(1_500_014_004, "该规则暂未接入质量引擎，无法在线测试");
    ErrorCode CLEAN_RULE_TEST_FAILED = new ErrorCode(1_500_014_005, "清洗测试失败：{}");


    // ========== 主数据--目录 2_000_000_000 ==========
    ErrorCode MASTER_CATALOG_NOT_EXISTS = new ErrorCode(2_000_000_000, "主数据目录不存在");
    ErrorCode MASTER_CATALOG_PARENT_NOT_EXISTS = new ErrorCode(2_000_000_001, "父级主数据目录不存在");
    ErrorCode MASTER_CATALOG_PARENT_TYPE_ERROR = new ErrorCode(2_000_000_002, "主数据节点下不能再创建子节点");
    ErrorCode MASTER_CATALOG_EXITS_CHILDREN = new ErrorCode(2_000_000_003, "存在子目录，无法删除");
    ErrorCode MASTER_CATALOG_EXISTS_MODEL = new ErrorCode(2_000_000_004, "该节点下已定义主数据模型，请先删除模型");
    ErrorCode MASTER_CATALOG_NAME_DUPLICATE = new ErrorCode(2_000_000_005, "同级下已存在同名节点");
    ErrorCode MASTER_CATALOG_TYPE_CANNOT_CHANGE = new ErrorCode(2_000_000_006, "节点类型不允许修改");

    // ========== 主数据--模型 2_000_000_100 ==========
    ErrorCode MASTER_MODEL_NOT_EXISTS = new ErrorCode(2_000_000_100, "主数据模型不存在");
    ErrorCode MASTER_MODEL_CATALOG_TYPE_ERROR = new ErrorCode(2_000_000_101, "主数据模型只能挂在主数据类型的节点下");
    ErrorCode MASTER_MODEL_CATALOG_HAS_MODEL = new ErrorCode(2_000_000_102, "该主数据节点已存在模型");
    ErrorCode MASTER_MODEL_TABLE_NAME_INVALID = new ErrorCode(2_000_000_103,
            "表名须以小写字母开头，仅含小写字母、数字、下划线");
    ErrorCode MASTER_MODEL_TABLE_NAME_DUPLICATE = new ErrorCode(2_000_000_104, "已存在同名的主数据物理表");
    ErrorCode MASTER_MODEL_NO_COLUMN = new ErrorCode(2_000_000_105, "请先设计模型字段");
    ErrorCode MASTER_MODEL_NO_PK = new ErrorCode(2_000_000_106, "主数据模型至少需要一个主键字段");
    ErrorCode MASTER_MODEL_COLUMN_NAME_INVALID = new ErrorCode(2_000_000_107,
            "字段名【{}】须以小写字母开头，仅含小写字母、数字、下划线");
    ErrorCode MASTER_MODEL_COLUMN_NAME_DUPLICATE = new ErrorCode(2_000_000_108, "字段名【{}】重复");
    ErrorCode MASTER_MODEL_RELEASED_CANNOT_MODIFY = new ErrorCode(2_000_000_109, "模型已发布，请先取消发布");
    ErrorCode MASTER_MODEL_NOT_RELEASED = new ErrorCode(2_000_000_110, "模型尚未发布");
    ErrorCode MASTER_MODEL_HAS_DISTRIBUTE = new ErrorCode(2_000_000_111, "模型存在关联的派发任务，请先删除派发任务");
    ErrorCode MASTER_MODEL_DATABASE_NOT_EXISTS = new ErrorCode(2_000_000_112, "模型绑定的数据源不存在");
    ErrorCode MASTER_MODEL_RELEASE_FAILED = new ErrorCode(2_000_000_113, "发布建表失败：{}");

    // ========== 主数据--数据维护 2_000_000_200 ==========
    ErrorCode MASTER_DATA_QUERY_COLUMN_INVALID = new ErrorCode(2_000_000_200, "查询字段【{}】不在模型字段范围内");
    ErrorCode MASTER_DATA_TYPE_ERROR = new ErrorCode(2_000_000_201, "{}");
    ErrorCode MASTER_DATA_QUERY_CONDITION_INVALID = new ErrorCode(2_000_000_202, "查询条件不正确：{}");

    // ========== 主数据工作台 2_000_000_400 ==========
    ErrorCode MASTER_DOMAIN_NOT_EXISTS = new ErrorCode(2_000_000_400, "主数据域不存在");
    ErrorCode MASTER_DOMAIN_CODE_DUPLICATE = new ErrorCode(2_000_000_401, "主数据域编码已存在");
    ErrorCode MASTER_DOMAIN_NAME_DUPLICATE = new ErrorCode(2_000_000_402, "主数据域名称已存在");
    ErrorCode MASTER_DOMAIN_DISABLED = new ErrorCode(2_000_000_403, "主数据域已停用");
    ErrorCode MASTER_DOMAIN_HAS_MODEL = new ErrorCode(2_000_000_404, "主数据域下存在模型，不能删除");
    ErrorCode MASTER_MODEL_DOMAIN_REQUIRED = new ErrorCode(2_000_000_405, "请选择主数据域");
    ErrorCode MASTER_MODEL_STRUCTURE_FROZEN = new ErrorCode(2_000_000_406, "已发布模型不能删除或更换目标数据源、物理表");
    ErrorCode MASTER_MODEL_PUBLISH_UNCERTAIN = new ErrorCode(2_000_000_407, "发布结果待核查，请先核验物理表");
    ErrorCode MASTER_MODEL_PUBLISH_CONFLICT = new ErrorCode(2_000_000_408, "模型状态已变化，请刷新后重试");
    ErrorCode MASTER_MODEL_PUBLISH_VERIFY_FAILED = new ErrorCode(2_000_000_409, "物理表与模型定义不一致，不能确认发布");
    ErrorCode MASTER_MODEL_APPROVAL_CONFIG_INVALID = new ErrorCode(2_000_000_410, "审批配置不正确");
    ErrorCode MASTER_CODE_RULE_NOT_EXISTS = new ErrorCode(2_000_000_411, "编码规则不存在");
    ErrorCode MASTER_CODE_RULE_CODE_DUPLICATE = new ErrorCode(2_000_000_412, "编码规则编码已存在");
    ErrorCode MASTER_CODE_RULE_INVALID = new ErrorCode(2_000_000_413, "编码规则配置不正确：{}");
    ErrorCode MASTER_CODE_RULE_EXHAUSTED = new ErrorCode(2_000_000_414, "编码流水号已超出配置位数");
    ErrorCode MASTER_MODEL_HAS_CODE_RULE = new ErrorCode(2_000_000_415, "模型存在编码规则，请先处理规则");
    ErrorCode MASTER_DATA_CHANGE_NOT_EXISTS = new ErrorCode(2_000_000_416, "主数据变更申请不存在");
    ErrorCode MASTER_DATA_CHANGE_STATE_INVALID = new ErrorCode(2_000_000_417, "变更状态不允许此操作，请刷新后核查");
    ErrorCode MASTER_DATA_REQUEST_CONFLICT = new ErrorCode(2_000_000_418, "请求标识已用于其他变更");
    ErrorCode MASTER_DATA_ROW_PENDING = new ErrorCode(2_000_000_419, "该数据存在未完成变更，请先处理审批或核查结果");
    ErrorCode MASTER_DATA_ROW_INVALID = new ErrorCode(2_000_000_420, "主数据内容不正确：{}");
    ErrorCode MASTER_DATA_ROW_CHANGED = new ErrorCode(2_000_000_421, "原数据已变化，不能应用此变更");
    ErrorCode MASTER_DATA_BPM_REVIEW_REQUIRED = new ErrorCode(2_000_000_422,
            "此申请由流程审批，请在流程中心处理后同步结果");
    ErrorCode MASTER_DATA_RESOLVE_EVIDENCE_REQUIRED = new ErrorCode(2_000_000_423, "请提供变更结果的核查证据");
    ErrorCode MASTER_DOMAIN_LEADER_REQUIRED = new ErrorCode(2_000_000_424, "请选择主数据域负责人");
    ErrorCode MASTER_DOMAIN_LEADER_INVALID = new ErrorCode(2_000_000_425, "主数据域负责人无效，请重新选择");

    // ========== 元数据--采集任务 2_000_002_000 ==========
    ErrorCode META_COLLECT_TASK_NOT_EXISTS = new ErrorCode(2_000_002_000, "元数据采集任务不存在");
    ErrorCode META_COLLECT_TASK_NAME_DUPLICATE = new ErrorCode(2_000_002_001, "已存在同名的采集任务");
    ErrorCode META_COLLECT_TASK_DATASOURCE_NOT_EXISTS = new ErrorCode(2_000_002_002, "采集任务关联的数据源不存在");
    ErrorCode META_COLLECT_TASK_CRON_INVALID = new ErrorCode(2_000_002_003, "Cron 表达式不合法");
    ErrorCode META_COLLECT_TASK_RUNNING = new ErrorCode(2_000_002_004, "采集任务正在执行中，请稍后再试");
    ErrorCode META_COLLECT_TASK_ENABLED_CANNOT_DELETE = new ErrorCode(2_000_002_005,
            "采集任务处于上线状态，请先下线再删除");
    ErrorCode META_COLLECT_TASK_DISABLED_CANNOT_RUN = new ErrorCode(2_000_002_006, "采集任务已下线，无法执行");

    // ========== 元数据--采集实例 2_000_002_100 ==========
    ErrorCode META_COLLECT_INSTANCE_NOT_EXISTS = new ErrorCode(2_000_002_100, "元数据采集实例不存在");

    // ========== 元数据--对象 2_000_002_200 ==========
    ErrorCode META_DB_NOT_EXISTS = new ErrorCode(2_000_002_200, "库元数据不存在");
    ErrorCode META_TABLE_NOT_EXISTS = new ErrorCode(2_000_002_201, "表元数据不存在");
    ErrorCode META_COLUMN_NOT_EXISTS = new ErrorCode(2_000_002_202, "字段元数据不存在");
    ErrorCode META_TABLE_NOT_PENDING_DELETE = new ErrorCode(2_000_002_203, "表元数据不处于待确认删除状态");
    ErrorCode META_DB_NOT_PENDING_DELETE = new ErrorCode(2_000_002_204, "库元数据不处于待确认删除状态");
    ErrorCode META_SNAPSHOT_NOT_EXISTS = new ErrorCode(2_000_002_205, "表结构快照不存在");
    ErrorCode META_TABLE_PUBLISH_VALIDATE_FAIL = new ErrorCode(2_000_002_206, "发布校验未通过：{}");
    ErrorCode META_TABLE_PENDING_CANNOT_PUBLISH = new ErrorCode(2_000_002_207,
            "对象处于待确认删除/已删除状态，不可发布");
    ErrorCode META_TABLE_NOT_PUBLISHED = new ErrorCode(2_000_002_208, "表元数据尚未发布");

    // ========== 元数据--标签 2_000_002_300 ==========
    ErrorCode META_TAG_NOT_EXISTS = new ErrorCode(2_000_002_300, "元数据标签不存在");
    ErrorCode META_TAG_NAME_DUPLICATE = new ErrorCode(2_000_002_301, "已存在同名的元数据标签");
    ErrorCode META_TAG_REFERENCED = new ErrorCode(2_000_002_302, "标签已被元数据对象引用，无法删除");

    // ========== 元数据维护并发控制 1_900_002_000 ==========
    ErrorCode META_BIZ_CONCURRENT_CHANGED = new ErrorCode(1_900_002_000, "元数据已被修改，请重新加载后再保存");
    ErrorCode META_TAG_BINDING_CONCURRENT_CHANGED = new ErrorCode(1_900_002_001, "对象标签已被修改，请重新加载后再保存");
    ErrorCode META_OBJECT_NOT_EDITABLE = new ErrorCode(1_900_002_002, "元数据对象不存在或已删除，无法维护");
    ErrorCode META_STATE_CONCURRENT_CHANGED = new ErrorCode(1_900_002_010, "元数据发布或定版状态已改变，请刷新后重试");
    ErrorCode META_SNAPSHOT_TABLE_MISMATCH = new ErrorCode(1_900_002_011, "结构快照不属于指定表");
    ErrorCode META_TABLE_NOT_FINALIZED = new ErrorCode(1_900_002_012, "表元数据尚未定版");
    ErrorCode META_SNAPSHOT_INVALID = new ErrorCode(1_900_002_013, "结构快照不完整或格式不正确");
    ErrorCode META_TABLE_PUBLISHED_READ_ONLY = new ErrorCode(1_900_002_014, "已发布表不可维护或删除，请先取消发布");
    ErrorCode META_TABLE_CATALOG_REFERENCED = new ErrorCode(1_900_002_015, "表已关联资产目录，不可删除");


    // ========== 数据质量--质量规则 1_500_015_000 ==========
    ErrorCode QUALITY_RULE_NOT_EXISTS = new ErrorCode(1_500_015_000, "质量规则不存在");
    ErrorCode QUALITY_RULE_NAME_DUPLICATE = new ErrorCode(1_500_015_001, "已存在同名的质量规则");
    ErrorCode QUALITY_RULE_CODE_DUPLICATE = new ErrorCode(1_500_015_002, "已存在同编码的质量规则");
    ErrorCode QUALITY_RULE_BUILTIN_CANNOT_DELETE = new ErrorCode(1_500_015_003, "内置规则不允许删除");
    ErrorCode QUALITY_RULE_STANDARD_CANNOT_DELETE = new ErrorCode(1_500_015_090,
            "标准物化的质量规则不允许删除，请废止或发布新版本标准");
    ErrorCode QUALITY_RULE_STANDARD_FIELD_IMMUTABLE = new ErrorCode(1_500_015_091,
            "标准物化规则的策略、参数和类型不可手工修改");
    // ========== 数据质量--规则集 1_500_015_100 ==========
    ErrorCode QUALITY_RULE_SET_NOT_EXISTS = new ErrorCode(1_500_015_100, "规则集不存在");
    ErrorCode QUALITY_RULE_SET_NAME_DUPLICATE = new ErrorCode(1_500_015_101, "已存在同名的规则集");
    ErrorCode QUALITY_RULE_SET_ITEM_EMPTY = new ErrorCode(1_500_015_102, "规则集至少需要一个规则");
    // ========== 数据质量--检查对象 1_500_015_200 ==========
    ErrorCode QUALITY_CHECK_OBJECT_NOT_EXISTS = new ErrorCode(1_500_015_200, "检查对象不存在");
    ErrorCode QUALITY_CHECK_OBJECT_NAME_DUPLICATE = new ErrorCode(1_500_015_201, "已存在同名的检查对象");
    // ========== 数据质量--质量任务 1_500_015_300 ==========
    ErrorCode QUALITY_TASK_NOT_EXISTS = new ErrorCode(1_500_015_300, "质量任务不存在");
    ErrorCode QUALITY_TASK_NAME_DUPLICATE = new ErrorCode(1_500_015_301, "已存在同名的质量任务");
    ErrorCode QUALITY_TASK_RUNNING = new ErrorCode(1_500_015_302, "质量任务正在执行中，请稍后再试");
    ErrorCode QUALITY_TASK_CRON_INVALID = new ErrorCode(1_500_015_303, "Cron 表达式不合法");
    ErrorCode QUALITY_TASK_INSTANCE_NOT_EXISTS = new ErrorCode(1_500_015_304, "任务实例不存在");
    ErrorCode QUALITY_TASK_RULE_EMPTY = new ErrorCode(1_500_015_305, "质量任务至少需要一条质量规则");
    ErrorCode QUALITY_RUN_FORBIDDEN = new ErrorCode(1_500_015_306, "执行身份无权使用该质量任务");
    ErrorCode QUALITY_RUN_NOT_READY = new ErrorCode(1_500_015_307, "质量任务须上线且配置有效检查对象和规则");
    ErrorCode QUALITY_RUN_KEY_CONFLICT = new ErrorCode(1_500_015_308, "质量运行标识对应的请求参数不一致");
    ErrorCode QUALITY_ETL_SNAPSHOT_LIMIT_EXCEEDED = new ErrorCode(1_500_015_309, "ETL 治理快照超过允许上限");
    ErrorCode QUALITY_ETL_PLAN_INVALID = new ErrorCode(1_500_015_310, "ETL 行级质量计划配置不合法");
    ErrorCode QUALITY_ETL_RESULT_INVALID = new ErrorCode(1_500_015_311, "ETL 内联质量结果不合法");
    ErrorCode QUALITY_ETL_REPORT_NOT_EXISTS = new ErrorCode(1_500_015_312, "ETL 内联质量报告不存在");
    // ========== 数据质量--任务模板 1_500_015_400 ==========
    ErrorCode QUALITY_TASK_TEMPLATE_NOT_EXISTS = new ErrorCode(1_500_015_400, "任务模板不存在");
    ErrorCode QUALITY_TASK_TEMPLATE_NAME_DUPLICATE = new ErrorCode(1_500_015_401, "已存在同名的任务模板");
    // ========== 数据质量--问题数据 1_500_015_500 ==========
    ErrorCode QUALITY_ISSUE_NOT_EXISTS = new ErrorCode(1_500_015_500, "问题数据不存在");
    ErrorCode QUALITY_ISSUE_ACCESS_FORBIDDEN = new ErrorCode(1_500_015_501,
            "当前身份无权查看问题数据明细或无法核验查询结果");
    ErrorCode QUALITY_ISSUE_ROW_STALE = new ErrorCode(1_900_015_502, "问题行或处置状态已变化，请刷新后重试");
    ErrorCode QUALITY_ISSUE_ACTION_INVALID = new ErrorCode(1_900_015_503, "问题行处置动作、备注或筛选字段不合法");
    // ========== 数据质量--修复工单 1_500_015_600 ==========
    ErrorCode QUALITY_REPAIR_ORDER_NOT_EXISTS = new ErrorCode(1_500_015_600, "修复工单不存在");
    ErrorCode QUALITY_REPAIR_SQL_INVALID = new ErrorCode(1_500_015_601, "修复SQL不合法：{}");
    ErrorCode QUALITY_REPAIR_SQL_DDL_FORBIDDEN = new ErrorCode(1_500_015_602, "修复SQL禁止使用 DDL 语句");
    ErrorCode QUALITY_REPAIR_SELF_APPROVE = new ErrorCode(1_500_015_603, "修复工单不能由创建人自己审批");
    ErrorCode QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH = new ErrorCode(1_500_015_604,
            "修复内容已变更，原审批失效，请重新审批");
    ErrorCode QUALITY_REPAIR_STATUS_INVALID = new ErrorCode(1_500_015_605, "修复工单当前状态不允许该操作");
    ErrorCode QUALITY_REPAIR_UNKNOWN_NO_REPLAY = new ErrorCode(1_500_015_606,
            "修复执行结果未知，禁止自动重放，请人工核对后再对账");
    ErrorCode QUALITY_REPAIR_ROW_LIMIT_EXCEEDED = new ErrorCode(1_500_015_607, "实际影响行数超过审批上限，已回滚");
    ErrorCode QUALITY_REPAIR_PRODUCT_UNSUPPORTED = new ErrorCode(1_500_015_608,
            "目标数据库产品尚未验证安全修复执行：{}");
    // ========== 数据质量--问题工单 1_500_015_700 ==========
    ErrorCode QUALITY_WORK_ORDER_NOT_EXISTS = new ErrorCode(1_500_015_700, "问题工单不存在");
    ErrorCode QUALITY_WORK_ORDER_OWNER_REQUIRED = new ErrorCode(1_500_015_701, "请指定工单责任人");
    ErrorCode QUALITY_WORK_ORDER_REPAIR_REQUIRED = new ErrorCode(1_500_015_702, "请先完成整改执行后再关闭工单");
    ErrorCode QUALITY_WORK_ORDER_RECHECK_REQUIRED = new ErrorCode(1_500_015_703, "整改后尚未复检通过，不能关闭工单");
    ErrorCode QUALITY_WORK_ORDER_STATUS_INVALID = new ErrorCode(1_500_015_705, "工单当前状态不允许该操作");
    ErrorCode QUALITY_WORK_ORDER_VERSION_CONFLICT = new ErrorCode(1_500_015_706, "工单已被他人更新，请刷新后重试");
    ErrorCode QUALITY_WORK_ORDER_SELF_CONFIRM = new ErrorCode(1_500_015_707, "办理人不能验收自己提交的整改");
    ErrorCode QUALITY_WORK_ORDER_UNASSIGNED = new ErrorCode(1_500_015_708, "待认责工单须先完成首次指派");
    ErrorCode QUALITY_WORK_ORDER_RECHECK_NOT_PASS = new ErrorCode(1_500_015_709,
            "当前提交尚未取得有效复检通过，不能确认关闭");
    ErrorCode QUALITY_WORK_ORDER_REQUEST_CONFLICT = new ErrorCode(1_500_015_710, "同一请求编号已绑定其他操作内容");
    ErrorCode QUALITY_WORK_ORDER_SUBMISSION_INVALID = new ErrorCode(1_500_015_711,
            "当前提交无效、已撤回或类型/整改依据不完整");
    ErrorCode QUALITY_WORK_ORDER_RAW_FORBIDDEN = new ErrorCode(1_500_015_712, "无权查看问题原文明细");
    ErrorCode QUALITY_WORK_ORDER_EXCEPTION_REQUIRED = new ErrorCode(1_500_015_713,
            "例外关闭必须提供范围和未来的到期时间");
    ErrorCode QUALITY_WORK_ORDER_NO_BASELINE = new ErrorCode(1_500_015_714,
            "人工问题须先建立有效检测基线，或走解释确认");
    ErrorCode QUALITY_WORK_ORDER_WAITING_DATA = new ErrorCode(1_500_015_715, "复检结果尚不可比，不能用于本次关闭");
    ErrorCode QUALITY_WORK_ORDER_CONFIRM_ACTION_INVALID = new ErrorCode(1_500_015_716,
            "待确认事项与验收动作不匹配、依据缺失或未填写退回原因");
    ErrorCode QUALITY_WORK_ORDER_ASSIGN_TARGET_REQUIRED = new ErrorCode(1_500_015_717, "请指定办理部门或办理人");
    ErrorCode QUALITY_WORK_ORDER_HISTORY_READONLY = new ErrorCode(1_500_015_718,
            "已有办理记录的工单不能按普通CRUD删除");
    ErrorCode QUALITY_WORK_ORDER_REPAIR_NOT_DONE = new ErrorCode(1_500_015_719,
            "平台修复须绑定本工单问题对象已审批且成功执行的修复单");
    ErrorCode QUALITY_WORK_ORDER_ETL_RECHECK_UNAVAILABLE = new ErrorCode(1_500_015_722,
            "ETL 报告工单尚无可验证的同批次复检凭证，当前不能复检或关闭");
    ErrorCode QUALITY_WORK_ORDER_ETL_REPORT_ALREADY_LINKED = new ErrorCode(1_500_015_723, "该 ETL 质量报告已关联工单");
    ErrorCode QUALITY_WORK_ORDER_ISSUE_SCOPE_INVALID = new ErrorCode(1_500_015_720,
            "提交的问题明细不属于当前工单，不能越权改绑");
    // ========== 数据质量--机构评分 1_500_015_800 ==========
    ErrorCode QUALITY_ORG_SCORE_NOT_EXISTS = new ErrorCode(1_500_015_800, "机构评分不存在");
    // ========== 数据质量--告警记录 1_500_015_900 ==========
    ErrorCode QUALITY_ALERT_NOT_EXISTS = new ErrorCode(1_500_015_900, "告警记录不存在");
    // ========== 数据质量--质检简报 1_500_016_000 ==========
    ErrorCode QUALITY_BRIEF_TEMPLATE_NOT_EXISTS = new ErrorCode(1_500_016_000, "简报模板不存在");
    ErrorCode QUALITY_BRIEF_NOT_EXISTS = new ErrorCode(1_500_016_001, "质检简报不存在");
    ErrorCode QUALITY_BRIEF_TEMPLATE_NAME_DUPLICATE = new ErrorCode(1_500_016_002, "已存在同名的简报模板");

    // ========== 主数据--派发 2_000_000_300 ==========
    ErrorCode MASTER_DISTRIBUTE_STOP_CONFLICT = new ErrorCode(2_000_000_311,
            "停止请求无效、运行状态已变化或停止凭证不一致，请刷新执行记录核查");
    ErrorCode MASTER_PUSH_CATEGORY_INVALID = new ErrorCode(1_900_003_000, "主数据推送类目参数错误：{}");
    ErrorCode MASTER_DISTRIBUTE_NOT_EXISTS = new ErrorCode(2_000_000_300, "主数据派发任务不存在");
    ErrorCode MASTER_DISTRIBUTE_RELEASED_CANNOT_MODIFY = new ErrorCode(2_000_000_301, "派发任务已发布，请先取消发布");
    ErrorCode MASTER_DISTRIBUTE_MODEL_NOT_RELEASED = new ErrorCode(2_000_000_302, "关联的主数据模型尚未发布");
    ErrorCode MASTER_DISTRIBUTE_CONFIG_INVALID = new ErrorCode(2_000_000_303, "派发配置不合法：{}");
    ErrorCode MASTER_DISTRIBUTE_CRON_INVALID = new ErrorCode(2_000_000_304, "Cron 表达式不合法");
    ErrorCode MASTER_DISTRIBUTE_RUNNING = new ErrorCode(2_000_000_305,
            "派发任务有等待、运行中或结果未知的记录，请先查看执行记录，禁止直接重跑");
    ErrorCode MASTER_DISTRIBUTE_NOT_RELEASED = new ErrorCode(2_000_000_306, "派发任务尚未发布");
    ErrorCode MASTER_DISTRIBUTE_LOG_NOT_EXISTS = new ErrorCode(2_000_000_307, "派发执行记录不存在");
    ErrorCode MASTER_DISTRIBUTE_REVIEW_REQUIRED = new ErrorCode(2_000_000_308,
            "请核实全部输出和终止证据；有活动运行或有效租约时不可处置");
    ErrorCode MASTER_DISTRIBUTE_REVIEW_CONFLICT = new ErrorCode(2_000_000_309,
            "派发核查记录或请求内容已变化，请刷新后核对原始凭证");
    ErrorCode MASTER_DISTRIBUTE_REVIEW_FORBIDDEN = new ErrorCode(2_000_000_310, "无主数据派发未知结果核查权限");


    ErrorCode META_COLLECT_SCOPE_INVALID = new ErrorCode(1_900_002_100, "采集范围无效或所选库表已不存在，请重新选择");
    ErrorCode META_COLLECT_SOURCE_INVALID = new ErrorCode(1_900_002_101, "采集连接或来源系统不可用");
    ErrorCode META_COLLECT_QUEUE_FULL = new ErrorCode(1_900_002_102, "采集等待队列已满，请稍后重试");
    ErrorCode META_COLLECT_ACTIVE = new ErrorCode(1_900_002_103, "存在等待或执行中的采集实例，不能修改或删除");
    ErrorCode META_COLLECT_SWITCH_INVALID = new ErrorCode(1_900_002_104, "状态值无效");
    ErrorCode META_FILE_NOT_EXISTS = new ErrorCode(1_900_002_200, "文件元数据不存在");
    ErrorCode META_FILE_CONCURRENT = new ErrorCode(1_900_002_201, "文件元数据已变更，请刷新后重试");
    ErrorCode META_FILE_SCOPE_LIMIT = new ErrorCode(1_900_002_202, "文件元数据范围超过上限，请选择更小的目录");
    ErrorCode STANDARD_CATALOG_INVALID = new ErrorCode(1_900_000_130, "标准目录参数错误：{}");
    ErrorCode STANDARD_CATALOG_CONFLICT = new ErrorCode(1_900_000_131, "记录已被其他操作修改，请刷新后重试");
    ErrorCode STANDARD_ELEMENT_RULE_NOT_EXISTS = new ErrorCode(1_900_000_132, "数据元关联规则不存在");
    ErrorCode STANDARD_ELEMENT_RULE_DUPLICATE = new ErrorCode(1_900_000_133, "数据元内已存在同名规则");
    ErrorCode STANDARD_CODE_MAPPING_NOT_EXISTS = new ErrorCode(1_900_000_134, "代码值映射不存在");
    ErrorCode STANDARD_CODE_MAPPING_DUPLICATE = new ErrorCode(1_900_000_135, "该原始值已经配置映射");
    ErrorCode STANDARD_CODE_ITEM_REFERENCED = new ErrorCode(1_900_000_136, "码值已被原始值映射引用，请先删除映射");

    // ========== 数据探查 1_900_019_000 ==========
    ErrorCode PROFILE_RUN_ACTIVE = new ErrorCode(1_900_019_000, "该表已有等待或执行中的探查，请等待完成后再发起");
    ErrorCode PROFILE_PRODUCT_UNSUPPORTED = new ErrorCode(1_900_019_001, "数据探查暂不支持该数据库类型：{}");
    ErrorCode PROFILE_NO_COLUMNS = new ErrorCode(1_900_019_002, "该表没有可探查的字段");
    ErrorCode PROFILE_DATASOURCE_MISSING = new ErrorCode(1_900_019_003, "元数据表未关联可用数据源，无法探查");
}
