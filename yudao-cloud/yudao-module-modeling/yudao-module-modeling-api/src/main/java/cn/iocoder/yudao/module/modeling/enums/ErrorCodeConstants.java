package cn.iocoder.yudao.module.modeling.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 *  错误码 Core 枚举类
 *
 */
public interface ErrorCodeConstants {

    // ========== 业务实体 1_500_050_000 ==========
    ErrorCode BUSINESS_ENTITY_NOT_EXISTS = new ErrorCode(1_500_050_000, "业务实体不存在");
    ErrorCode BUSINESS_ENTITY_CODE_DUPLICATE = new ErrorCode(1_500_050_001, "数据板块内已存在相同英文名称的业务实体");
    ErrorCode BUSINESS_ENTITY_REVISION_CONFLICT = new ErrorCode(1_500_050_002, "业务实体已变化，请刷新后重试");
    ErrorCode BUSINESS_ENTITY_ONLINE = new ErrorCode(1_500_050_003, "请先下线业务实体，再修改或删除");
    ErrorCode BUSINESS_ENTITY_TYPE_IMMUTABLE = new ErrorCode(1_500_050_004, "业务实体的所属板块及类型创建后不可修改");
    ErrorCode BUSINESS_ENTITY_RELATION_INVALID = new ErrorCode(1_500_050_005, "业务实体关系不合法：{}");
    ErrorCode BUSINESS_ENTITY_REFERENCED = new ErrorCode(1_500_050_006, "业务实体仍被其他实体或模型引用，不能删除");
    ErrorCode BUSINESS_ENTITY_STATUS_INVALID = new ErrorCode(1_500_050_008, "业务实体状态不合法");

    // ========== 业务限定 1_500_049_000 ==========
    ErrorCode BUSINESS_CONDITION_NOT_EXISTS = new ErrorCode(1_500_049_000, "业务限定不存在");
    ErrorCode BUSINESS_CONDITION_CODE_DUPLICATE = new ErrorCode(1_500_049_001, "业务限定英文编码已存在");
    ErrorCode BUSINESS_CONDITION_REVISION_CONFLICT = new ErrorCode(1_500_049_002, "业务限定已变化，请刷新后重试");
    ErrorCode BUSINESS_CONDITION_NOT_DRAFT = new ErrorCode(1_500_049_003, "请先停用业务限定，再修改或删除");
    ErrorCode BUSINESS_CONDITION_UNSUPPORTED = new ErrorCode(1_500_049_004, "当前仅支持基础限定和全时段统一计算逻辑");
    ErrorCode BUSINESS_CONDITION_EXPRESSION_INVALID = new ErrorCode(1_500_049_005, "请填写不超过4000字的计算逻辑定义");
    ErrorCode BUSINESS_CONDITION_SOURCE_DOMAIN_INVALID = new ErrorCode(1_500_049_006,
            "业务限定必须与来源逻辑模型属于同一数据域");

    // ========== 指标模型 1_500_048_000 ==========
    ErrorCode INDICATOR_NOT_EXISTS = new ErrorCode(1_500_048_000, "指标模型不存在");
    ErrorCode INDICATOR_CODE_DUPLICATE = new ErrorCode(1_500_048_001, "指标编码已存在");
    ErrorCode INDICATOR_REVISION_CONFLICT = new ErrorCode(1_500_048_002, "指标已变化，请刷新后重试");
    ErrorCode INDICATOR_NOT_DRAFT = new ErrorCode(1_500_048_003, "请先停用指标，再修改或删除");
    ErrorCode INDICATOR_REFERENCED = new ErrorCode(1_500_048_004, "原子指标被派生指标引用，不能修改、停用或删除");
    ErrorCode INDICATOR_SOURCE_INVALID = new ErrorCode(1_500_048_005, "请选择已启用且当前设计已发布的逻辑模型");
    ErrorCode INDICATOR_FIELD_INVALID = new ErrorCode(1_500_048_006, "所选字段不存在于来源模型当前设计");
    ErrorCode INDICATOR_BASE_INVALID = new ErrorCode(1_500_048_007, "请选择已启用的原子指标作为派生来源");
    ErrorCode INDICATOR_DEFINITION_INVALID = new ErrorCode(1_500_048_008, "指标定义不合法：{}");
    ErrorCode INDICATOR_TYPE_IMMUTABLE = new ErrorCode(1_500_048_009, "指标类型创建后不可修改");
    ErrorCode INDICATOR_SOURCE_FORBIDDEN = new ErrorCode(1_500_048_010, "没有查询逻辑模型的权限，无法配置来源字段");
    ErrorCode INDICATOR_SOURCE_REFERENCED = new ErrorCode(1_500_048_011, "逻辑模型已被指标或业务限定引用，不能删除或废弃");

    // ========== 数仓分层 1_500_002_000 ==========
    ErrorCode DW_LAYER_NOT_EXISTS = new ErrorCode(1_500_002_000, "数仓分层不存在");
    ErrorCode DW_LAYER_EXITS_CHILDREN = new ErrorCode(1_500_002_001, "存在子数仓分层，无法删除");
    ErrorCode DW_LAYER_PARENT_NOT_EXITS = new ErrorCode(1_500_002_002, "父级数仓分层不存在");
    ErrorCode DW_LAYER_PARENT_ERROR = new ErrorCode(1_500_002_003, "不能设置自己为父数仓分层");
    ErrorCode DW_LAYER_LAYER_NAME_DUPLICATE = new ErrorCode(1_500_002_004, "已经存在该分层名称的数仓分层");
    ErrorCode DW_LAYER_LAYER_CODE_DUPLICATE = new ErrorCode(1_500_002_008, "分层编码已存在");
    ErrorCode DW_LAYER_LAYER_CODE_REQUIRED = new ErrorCode(1_500_002_009, "实际分层节点的分层编码不能为空");
    ErrorCode DW_LAYER_PARENT_IS_CHILD = new ErrorCode(1_500_002_005, "不能设置自己的子DwLayer为父DwLayer");
    ErrorCode DW_LAYER_LEVEL_EXCEED_LIMIT = new ErrorCode(1_500_002_006, "数仓分层仅支持两级，不能在第二层下继续新增");
    ErrorCode DW_LAYER_BUILTIN_CANNOT_DELETE = new ErrorCode(1_500_002_007, "内置数仓分层不允许删除");

    // ========== 数据分域 1_500_030_000 ==========
    ErrorCode DATA_DOMAIN_NOT_EXISTS = new ErrorCode(1_500_030_000, "数据分域不存在");
    ErrorCode DATA_DOMAIN_NAME_DUPLICATE = new ErrorCode(1_500_030_001, "已存在同名数据分域");
    ErrorCode DATA_DOMAIN_HAS_SUBJECTS = new ErrorCode(1_500_030_003, "板块存在主题域，无法删除");
    ErrorCode DATA_DOMAIN_NAMING_RULE_INVALID = new ErrorCode(1_500_030_004, "请完整配置逻辑表的命名规则");
    ErrorCode DATA_DOMAIN_ENABLED = new ErrorCode(1_500_030_005, "请先禁用数据板块再修改或删除");
    ErrorCode DATA_DOMAIN_CODE_DUPLICATE = new ErrorCode(1_500_030_002, "已存在相同英文缩写的数据分域");

    // ========== 业务分类 1_500_031_000 ==========
    ErrorCode BIZ_CATEGORY_NOT_EXISTS = new ErrorCode(1_500_031_000, "业务分类不存在");
    ErrorCode BIZ_CATEGORY_EXITS_CHILDREN = new ErrorCode(1_500_031_001, "存在子业务分类，无法删除");
    ErrorCode BIZ_CATEGORY_PARENT_NOT_EXITS = new ErrorCode(1_500_031_002, "父级业务分类不存在");
    ErrorCode BIZ_CATEGORY_PARENT_ERROR = new ErrorCode(1_500_031_003, "不能设置自己或自己的子级为上级分类");
    ErrorCode BIZ_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_500_031_004, "同级下已存在同名业务分类");
    // ========== 主题域 1_500_032_000 ==========
    ErrorCode SUBJECT_DOMAIN_NOT_EXISTS = new ErrorCode(1_500_032_000, "主题域不存在");
    ErrorCode SUBJECT_DOMAIN_EXITS_CHILDREN = new ErrorCode(1_500_032_001, "存在子主题域，无法删除");
    ErrorCode SUBJECT_DOMAIN_PARENT_NOT_EXITS = new ErrorCode(1_500_032_002, "父级主题域不存在");
    ErrorCode SUBJECT_DOMAIN_PARENT_ERROR = new ErrorCode(1_500_032_003, "不能设置自己或自己的子级为上级主题域");
    ErrorCode SUBJECT_DOMAIN_BOARD_MISMATCH = new ErrorCode(1_500_032_005, "上级主题域必须属于同一数据板块");
    ErrorCode SUBJECT_DOMAIN_CODE_DUPLICATE = new ErrorCode(1_500_032_006, "板块内主题域编码或英文缩写已存在");
    ErrorCode SUBJECT_DOMAIN_BOARD_IMMUTABLE = new ErrorCode(1_500_032_007, "主题域创建后不能更换所属数据板块");
    ErrorCode SUBJECT_DOMAIN_ENABLED = new ErrorCode(1_500_032_008, "请先禁用主题域再修改或删除");
    ErrorCode SUBJECT_DOMAIN_DEPTH_EXCEEDED = new ErrorCode(1_500_032_009, "主题域最多支持五级");
    ErrorCode SUBJECT_DOMAIN_NAME_DUPLICATE = new ErrorCode(1_500_032_004, "同级下已存在同名主题域");

    // ========== 逻辑模型 1_500_035_000 ==========
    ErrorCode LOGIC_MODEL_NOT_EXISTS = new ErrorCode(1_500_035_000, "逻辑模型不存在");
    ErrorCode LOGIC_MODEL_NAME_DUPLICATE = new ErrorCode(1_500_035_001, "已存在同名或同编码的逻辑模型");
    ErrorCode LOGIC_MODEL_DISCARDED = new ErrorCode(1_500_035_005, "模型已废弃，无法操作");
    ErrorCode LOGIC_MODEL_FIELDS_EMPTY = new ErrorCode(1_500_035_007, "模型字段不能为空");
    ErrorCode LOGIC_RELATION_NOT_EXISTS = new ErrorCode(1_500_035_008, "逻辑模型关系不存在");
    ErrorCode LOGIC_RELATION_FIELD_NOT_EXISTS = new ErrorCode(1_500_035_009, "关系字段在模型当前设计中不存在：{}");
    ErrorCode LOGIC_RELATION_DUPLICATE = new ErrorCode(1_500_035_010, "两个模型间相同字段的关系已存在");
    ErrorCode LOGIC_TABLE_ENABLED = new ErrorCode(1_500_035_015, "开启状态的逻辑表不可删除，请先停用");
    // ========== 物理模型 1_500_036_000 ==========
    ErrorCode PHYSICAL_MODEL_DATABASE_NOT_EXISTS = new ErrorCode(1_500_036_005, "目标数据源不存在");
    ErrorCode PHYSICAL_MODEL_TABLE_META_FAILED = new ErrorCode(1_500_036_008, "读取数据源表结构失败：{}");

    // ========== 模型规范归属 1_500_039_000 ==========
    ErrorCode MODEL_LAYER_REQUIRED = new ErrorCode(1_500_039_000, "请选择模型所属的数仓分层");
    ErrorCode MODEL_LAYER_NOT_EXISTS = new ErrorCode(1_500_039_001, "所选数仓分层不存在");
    ErrorCode MODEL_LAYER_NOT_LEAF = new ErrorCode(1_500_039_002, "只能选择实际分层节点，不能选择分层分类节点");
    ErrorCode MODEL_LAYER_DISABLED = new ErrorCode(1_500_039_003, "所选数仓分层已停用");
    ErrorCode MODEL_SUBJECT_DOMAIN_NOT_EXISTS = new ErrorCode(1_500_039_004, "所选主题域不存在");
    ErrorCode MODEL_SUBJECT_DOMAIN_DISABLED = new ErrorCode(1_500_039_005, "所选主题域已停用");
    ErrorCode MODEL_TABLE_TYPE_INVALID = new ErrorCode(1_500_039_006, "表类型不合法：{}");
    ErrorCode MODEL_TABLE_ENGLISH_NAME_REQUIRED = new ErrorCode(1_500_039_007, "请填写表英文名");
    ErrorCode MODEL_TABLE_ENGLISH_NAME_INVALID = new ErrorCode(1_500_039_008,
            "表英文名仅允许小写字母、数字和下划线，且以字母开头");
    ErrorCode MODEL_TABLE_TYPE_REQUIRED = new ErrorCode(1_500_039_009, "请先确认模型的表类型，再发布当前设计");
    ErrorCode MODEL_TABLE_NAME_TOO_LONG = new ErrorCode(1_500_039_010,
            "分层前缀与表英文名拼接后超过 {} 个字符，请缩短表英文名");

    // ========== 物理模型部署 1_500_040_000 ==========

    // ========== 表类型专属配置 1_500_041_000 ==========
    ErrorCode MODEL_TYPE_CONFIG_INCOMPLETE = new ErrorCode(1_500_041_000, "{}的专属配置不完整，发布前请补齐：{}");
    ErrorCode MODEL_TYPE_CONFIG_FIELD_NOT_EXISTS = new ErrorCode(1_500_041_001,
            "专属配置引用的字段不存在于当前设计：{}");
    ErrorCode MODEL_TYPE_CONFIG_INVALID_VALUE = new ErrorCode(1_500_041_002, "专属配置取值不合法：{}");

    // ========== 模型数据域归属校验 ==========
    ErrorCode MODEL_DATA_DOMAIN_REQUIRED = new ErrorCode(1_500_039_014, "请为模型指定数据域归属后再发布");
    ErrorCode MODEL_SUBJECT_DOMAIN_REQUIRED = new ErrorCode(1_500_039_015, "DWD 及以下发布必须选择主题域");
    ErrorCode MODEL_DATA_DOMAIN_NOT_EXISTS = new ErrorCode(1_500_039_016, "所选数据域不存在");
    ErrorCode MODEL_DATA_DOMAIN_DISABLED = new ErrorCode(1_500_039_026, "所选数据域已停用");

    ErrorCode MODEL_OWNERSHIP_INCOMPLETE = new ErrorCode(1_500_039_018, "模型归属检查未通过：{}");
    ErrorCode MODEL_BIZ_CATEGORY_REQUIRED = new ErrorCode(1_500_039_027, "请为非贴源层模型选择业务分类后再发布");
    ErrorCode MODEL_BIZ_CATEGORY_NOT_EXISTS = new ErrorCode(1_500_039_028, "所选业务分类不存在");
    ErrorCode MODEL_BIZ_CATEGORY_DISABLED = new ErrorCode(1_500_039_029, "所选业务分类已停用");
    ErrorCode MODEL_SUBJECT_DATA_DOMAIN_MISMATCH = new ErrorCode(1_500_039_032, "所选主题域不属于当前数据板块");
    ErrorCode MODEL_BIZ_CATEGORY_DATA_DOMAIN_MISMATCH = new ErrorCode(1_500_039_030,
            "模型数据域不在所选业务分类的关联域内");
    ErrorCode BIZ_CATEGORY_HAS_MODEL_REF = new ErrorCode(1_500_039_031, "业务分类仍被模型引用，无法删除");
    ErrorCode MODEL_REFERENCE_IN_USE = new ErrorCode(1_500_039_019, "规划对象已被模型引用，不能删除或改变其归属");
    ErrorCode MODEL_KIND_INVALID = new ErrorCode(1_500_039_020, "不支持的模型或规划对象类型");
    ErrorCode MODEL_PUBLISH_CONTENT_CHANGED = new ErrorCode(1_500_039_023,
            "发布校验期间模型当前设计或依赖已变化，请刷新并重新发布");
    ErrorCode MODEL_FIELD_DECIMAL_UNCONFIRMED = new ErrorCode(1_500_039_025,
            "字段 {} 的总精度或小数位数未确认，请在当前设计中明确填写，旧长度/精度不会自动转换");

    // ========== 模型物化发布 1_500_044_000 ==========
    ErrorCode MODEL_MATERIALIZED_NOT_EXISTS = new ErrorCode(1_500_044_000, "物化发布记录不存在");
    ErrorCode MODEL_MATERIALIZED_MODE_INVALID = new ErrorCode(1_500_044_002, "发布模式不合法：{}");
    ErrorCode MODEL_MATERIALIZED_MODEL_UNPUBLISHED = new ErrorCode(1_500_044_003,
            "模型当前设计尚未发布，或模型已停用、废弃");
    ErrorCode MODEL_MATERIALIZED_TARGET_BUSY = new ErrorCode(1_500_044_004,
            "该物理目标已有执行中或待对账部署，请先核对结果");
    ErrorCode MODEL_MATERIALIZED_OUTCOME_UNKNOWN = new ErrorCode(1_500_044_005,
            "部署结果尚未确认，请先执行对账；禁止直接重建或覆盖");
    ErrorCode MODEL_MATERIALIZED_PRODUCT_UNSUPPORTED = new ErrorCode(1_500_044_006,
            "当前数据库产品未验证冻结部署、事务锁及恢复能力，仅支持 PostgreSQL");

    // ========== 统一模型设计与部署 1_500_046_000 ==========
    ErrorCode MODEL_DESIGN_REVISION_CONFLICT = new ErrorCode(1_500_046_023, "模型设计已变化，请重新加载后保存");
    ErrorCode MODEL_FIELD_IDENTITY_INVALID = new ErrorCode(1_500_046_024, "字段名称或稳定标识重复、缺失或无效");
    ErrorCode MODEL_FIELD_REFERENCED = new ErrorCode(1_500_046_025, "字段被模型关系引用，须先修改关系：{}");
    ErrorCode MODEL_DESIGN_IN_USE = new ErrorCode(1_500_046_026,
            "模型存在关联关系或执行中、待对账部署，请先处理引用与任务");
    ErrorCode MODEL_RELATION_REVISION_CONFLICT = new ErrorCode(1_500_046_027, "关系或关联模型已变化，请刷新后重试");
    ErrorCode MODEL_REFERENCE_INVALID = new ErrorCode(1_500_046_012, "外部引用不存在、当前设计未发布或已失效：{}");

    // ========== 概念模型业务定义 1_500_047_000 ==========
    ErrorCode CONCEPT_MODEL_NOT_EXISTS = new ErrorCode(1_500_047_000, "概念模型不存在");
    ErrorCode CONCEPT_MODEL_DUPLICATE = new ErrorCode(1_500_047_001, "概念模型编码或同数据域下的模型名称已存在");
    ErrorCode CONCEPT_MODEL_REVISION_CONFLICT = new ErrorCode(1_500_047_002, "概念模型已被修改，请重新加载后再操作");
    ErrorCode CONCEPT_MODEL_NOT_DRAFT = new ErrorCode(1_500_047_003, "已启用的概念模型不能修改或删除，请先停用");
    ErrorCode CONCEPT_DESIGN_INVALID = new ErrorCode(1_500_047_004, "概念设计不合法：{}");
    ErrorCode CONCEPT_MODEL_EMPTY = new ErrorCode(1_500_047_005, "概念模型至少包含一个实体后才能启用");
}
