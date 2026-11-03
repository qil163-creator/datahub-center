import type { QualityTaskApi } from '#/api/governance/quality/task';

import {
  auditParameterDefaults,
  auditParameterIssue,
  auditReferencedColumns,
  automaticAuditStrategies,
} from './modules/audit-rule-parameters';

export const emptyImage = '/images/quality-reference/empty.png';

export const dimensionOptions = [
  { label: '准确性', value: 'ACCURACY' },
  { label: '规范性', value: 'NORMALIZATION' },
  { label: '时效性', value: 'TIMELINESS' },
  { label: '一致性', value: 'CONSISTENCY' },
  { label: '完整性', value: 'COMPLETENESS' },
];
export const statusOptions = [
  { label: '上线', value: 0 },
  { label: '下线', value: 1 },
];
export const strategyOptions = [
  { label: '对象并行', value: 'PARALLEL' },
  { label: '对象串行', value: 'SERIAL' },
];
/** 参考系统明确标灰的规则，仅展示目录；可执行规则始终由服务端提供。 */
const unavailableRules: [string, string, string, string][] = [
  ['203', 'ACCURACY', '必填字段非空校验', '检查强制必填字段是否存在空值'],
  [
    '204',
    'ACCURACY',
    '非法占位值识别',
    '检查是否使用非法占位（如N/A、-999）替代有效值',
  ],
  [
    '206',
    'ACCURACY',
    '整记录缺失检查校验',
    '检查是否存在除主键外字段全为空的记录',
  ],
  [
    '207',
    'ACCURACY',
    '条件字段完整性校验',
    '检查满足某条件时，相关字段是否已填写',
  ],
  [
    '403',
    'NORMALIZATION',
    '跨表字段值一致性校验',
    '检查同一字段在不同表间是否值一致',
  ],
  ['404', 'NORMALIZATION', '外键有效性校验', '检查外键值是否存在于目标主表中'],
  [
    '405',
    'NORMALIZATION',
    '状态字段一致性校验',
    '检查状态与其他字段值是否存在逻辑冲突',
  ],
  [
    '504',
    'TIMELINESS',
    '时间字段先后顺序校验',
    '流程控制、事件日志、审批流程等',
  ],
  [
    '307',
    'CONSISTENCY',
    '数值字段逻辑值合法性校验',
    '校验字段值是否违反业务规则（如库存不为负，销售额不为负）',
  ],
  [
    '308',
    'CONSISTENCY',
    '数值字段异常值识别',
    '检测异常的极端数值或明显错误数据',
  ],
  ['309', 'CONSISTENCY', '脏数据识别', '标记无意义、无效或错误占位的数据值'],
  ['310', 'CONSISTENCY', '单字段唯一性校验', '检查字段值是否唯一（如订单号）'],
  ['113', 'COMPLETENESS', '逻辑模型一致性校验', '检查表结构是否与数据模型一致'],
  [
    '114',
    'COMPLETENESS',
    '字段元信息完整性校验',
    '检查字段是否配置必要的元信息（类型、说明等）',
  ],
  [
    '116',
    'COMPLETENESS',
    '数值字段逻辑关系校验',
    '检查多个数值字段之间的业务逻辑关系',
  ],
  [
    '117',
    'COMPLETENESS',
    '状态字段依赖项校验',
    '检查某状态下是否填写了必填字段',
  ],
  [
    '119',
    'COMPLETENESS',
    '敏感字段脱敏校验',
    '检查敏感字段是否存在明文泄露风险',
  ],
  [
    '109',
    'COMPLETENESS',
    '日期时间格式校验',
    '检查字段是否为合法的时间格式（如yyyy-MM-dd）',
  ],
];
export function displayRuleCatalog(rules: QualityTaskApi.CatalogRule[]) {
  const existing = new Set(rules.map((row) => row.ruleCode));
  return [
    ...rules,
    ...unavailableRules
      .filter(([code]) => !existing.has(code))
      .map(([ruleCode, qualityDim, ruleName, description]) => ({
        ruleCode,
        qualityDim,
        ruleName,
        description,
        strategyKey: '',
        enabled: false,
        sampleSupported: false,
      })),
  ];
}
const ruleIcons: Record<string, string> = {
  CHARACTER_VALIDATION: 'character',
  NUMERIC_RANGE_VALIDATION: 'numeric-range',
  COMPOSITE_UNIQUENESS_VALIDATION: 'multi-unique',
  GROUP_FIELD_COMPLETENESS: 'group-completeness',
  TIME_ORDER_VALIDATION: 'time-order',
  MUTUAL_EXCLUSION_VALIDATION: 'mutex',
  LENGTH_VALIDATION: 'length',
  DECIMAL_PRECISION_VALIDATION: 'precision',
  ENUM_VALIDATION: 'enum',
  FOREIGN_KEY_VALIDATION: 'master-reference',
};
const unavailableIcons: Record<string, string> = {
  '203': 'required',
  '204': 'placeholder',
  '206': 'missing-record',
  '207': 'conditional-completeness',
  '403': 'cross-table',
  '404': 'foreign-key',
  '405': 'state-consistency',
  '504': 'time-precedence',
  '307': 'numeric-logic',
  '308': 'outlier',
  '309': 'dirty-data',
  '310': 'single-unique',
  '113': 'model-consistency',
  '114': 'metadata-completeness',
  '116': 'numeric-relation',
  '117': 'state-required',
  '119': 'masking',
  '109': 'date-format',
};
export function ruleIcon(strategyKey: string, ruleCode?: string) {
  return `/images/quality-reference/rule-${ruleIcons[strategyKey] ?? unavailableIcons[ruleCode ?? ''] ?? 'character'}.png`;
}
export function dimensionLabel(value?: string) {
  return (
    dimensionOptions.find((entry) => entry.value === value)?.label ??
    value ??
    '-'
  );
}
export function newTask(): QualityTaskApi.Task {
  return {
    taskName: '',
    taskCategory: undefined,
    executeStrategy: 'PARALLEL',
    scheduleType: 'MANUAL',
    taskStatus: 0,
    priority: 'MID',
    retryCount: 1,
    retryInterval: 1,
    delayExecTime: 1,
    workerGroup: 'default',
    objects: [],
    evaluations: [],
  };
}
export function newEvaluation(
  rule: QualityTaskApi.CatalogRule,
): QualityTaskApi.Evaluation {
  const defaults: Record<string, Record<string, unknown>> = {
    CHARACTER_VALIDATION: {
      useRegex: false,
      allowedTypes: ['digit'],
      ignoreNullValue: true,
    },
    NUMERIC_RANGE_VALIDATION: { includeBoundary: true },
    GROUP_FIELD_COMPLETENESS: { fillStrategy: 1 },
    TIME_ORDER_VALIDATION: {
      conditions: [{ leftField: '', operator: '<', rightField: '' }],
    },
    MUTUAL_EXCLUSION_VALIDATION: { condition: 'NOT_NULL', trueValues: [] },
    LENGTH_VALIDATION: { ignoreNullValue: true },
    DECIMAL_PRECISION_VALIDATION: {
      scale: 2,
      skipInteger: true,
      ignoreNullValue: true,
    },
    ENUM_VALIDATION: {
      ignoreCase: true,
      ignoreNullValue: true,
      useCodeTable: true,
      validValues: [],
      valueLabels: [],
    },
  };
  return {
    objectKey: '',
    evaluationName: '',
    ruleCode: rule.ruleCode,
    strategyKey: rule.strategyKey,
    qualityDim: rule.qualityDim,
    severity: 'CRITICAL',
    status: 0,
    columnNames:
      rule.strategyKey === 'MUTUAL_EXCLUSION_VALIDATION' ? ['', ''] : [],
    config:
      defaults[rule.strategyKey] ?? auditParameterDefaults(rule.strategyKey),
  };
}
export function validateEvaluation(
  value: QualityTaskApi.Evaluation,
  context: 'standard' | 'task' = 'task',
) {
  value = {
    ...value,
    columnNames: auditReferencedColumns(
      value.strategyKey,
      value.config,
      value.columnNames,
    ),
  };
  if (!value.evaluationName.trim()) return '请输入评测名称';
  const parameterIssue = auditParameterIssue(value.strategyKey, value.config);
  if (parameterIssue) return parameterIssue;
  if (
    !value.objectKey &&
    (context === 'task' ||
      [
        'CROSS_TABLE_CONSISTENCY',
        'FOREIGN_KEY_VALIDATION',
        'MASTER_DATA_REF_VALIDATION',
      ].includes(value.strategyKey))
  )
    return '请选择评测对象';
  if (value.strategyKey === 'TIME_ORDER_VALIDATION') {
    const conditions = value.config.conditions as {
      leftField: string;
      rightField: string;
    }[];
    if (
      !conditions?.length ||
      conditions.some((entry) => !entry.leftField || !entry.rightField)
    )
      return '请选择完整的时间顺序字段';
  } else if (
    !automaticAuditStrategies.has(value.strategyKey) &&
    (context === 'task' ||
      [
        'FOREIGN_KEY_VALIDATION',
        'MASTER_DATA_REF_VALIDATION',
        'MUTUAL_EXCLUSION_VALIDATION',
      ].includes(value.strategyKey)) &&
    (value.columnNames.length === 0 ||
      value.columnNames.some((column) => !column.trim()))
  )
    return '请选择检查字段';
  if (new Set(value.columnNames).size !== value.columnNames.length)
    return '检查字段不能重复';
  if (
    [
      'COMPOSITE_UNIQUENESS_VALIDATION',
      'GROUP_FIELD_COMPLETENESS',
      'MUTUAL_EXCLUSION_VALIDATION',
    ].includes(value.strategyKey) &&
    value.columnNames.length < 2
  )
    return '请至少选择两个检查字段';
  if (
    value.strategyKey === 'CHARACTER_VALIDATION' &&
    (value.config.useRegex
      ? !String(value.config.regex ?? '').trim()
      : !(value.config.allowedTypes as string[])?.length)
  )
    return '请配置允许字符类型或正则表达式';
  if (value.strategyKey === 'NUMERIC_RANGE_VALIDATION') {
    const decimal = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/u;
    const values = [value.config.minValue, value.config.maxValue].filter(
      (entry) => entry !== undefined && entry !== null && entry !== '',
    );
    if (values.length === 0) return '请至少配置一个数值边界';
    if (values.some((entry) => !decimal.test(String(entry))))
      return '数值边界必须为十进制数字';
  }
  if (
    value.strategyKey === 'LENGTH_VALIDATION' &&
    (value.config.minLength ?? '') === '' &&
    (value.config.maxLength ?? '') === ''
  )
    return '请至少配置一个长度边界';
  if (
    value.strategyKey === 'ENUM_VALIDATION' &&
    (value.config.useCodeTable
      ? !value.config.codeSetId
      : !(value.config.validValues as string[])?.length ||
        (value.config.validValues as string[]).some((entry) => !entry.trim()))
  )
    return '请选择码表或添加枚举值';
  if (
    value.strategyKey === 'MUTUAL_EXCLUSION_VALIDATION' &&
    value.config.condition === 'IS_TRUE' &&
    value.columnNames.some(
      (_, index) => !(value.config.trueValues as string[])?.[index]?.trim(),
    )
  )
    return '请填写每个检查字段的 TRUE 值';
  if (
    [
      'CROSS_TABLE_CONSISTENCY',
      'FOREIGN_KEY_VALIDATION',
      'MASTER_DATA_REF_VALIDATION',
    ].includes(value.strategyKey) &&
    (!value.config.referenceObjectKey || !value.config.refColumn)
  )
    return '请选择检查对象和引用字段';
  return undefined;
}
export function editableEvaluation(value: QualityTaskApi.Evaluation) {
  const copy = JSON.parse(JSON.stringify(value)) as QualityTaskApi.Evaluation;
  if (copy.strategyKey === 'ENUM_VALIDATION') {
    const labels = (copy.config.valueLabels ?? []) as {
      label: string;
      value: string;
    }[];
    const labeled = new Set(labels.map((entry) => entry.value));
    const values = (copy.config.validValues ?? []) as string[];
    copy.config.valueLabels = [
      ...labels,
      ...values
        .filter((entry) => !labeled.has(entry))
        .map((entry) => ({ value: entry, label: entry })),
    ];
  }
  return copy;
}
export function taskPayload(value: QualityTaskApi.Task): QualityTaskApi.Task {
  const copy = JSON.parse(JSON.stringify(value)) as QualityTaskApi.Task;
  delete copy.objectIds;
  delete copy.ruleIds;
  return copy;
}
