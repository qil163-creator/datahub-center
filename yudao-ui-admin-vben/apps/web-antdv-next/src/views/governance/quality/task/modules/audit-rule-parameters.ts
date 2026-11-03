import type { CleanParameterField } from '../../../cleanrule/modules/clean-rule-parameters';

export const extendedAuditStrategies = new Set([
  'COLUMN_METADATA_COMPLETENESS',
  'CONDITIONAL_REQUIRED_VALIDATION',
  'CROSS_TABLE_CONSISTENCY',
  'DATE_FORMAT_VALIDATION',
  'MODEL_STRUCTURE_VALIDATION',
  'NOT_NULL_VALIDATION',
  'NUMERIC_OUTLIER_VALIDATION',
  'NUMERIC_RELATION_VALIDATION',
  'PLACEHOLDER_VALIDATION',
  'RECORD_COMPLETENESS_VALIDATION',
  'SENSITIVE_MASK_VALIDATION',
  'STATE_CONSISTENCY_VALIDATION',
  'UNIQUE_VALIDATION',
]);
export const metadataAuditStrategies = new Set([
  'COLUMN_METADATA_COMPLETENESS',
  'MODEL_STRUCTURE_VALIDATION',
]);
export const automaticAuditStrategies = new Set([
  ...metadataAuditStrategies,
  'RECORD_COMPLETENESS_VALIDATION',
]);

const text = (
  key: string,
  label: string,
  required = false,
): CleanParameterField => ({
  key,
  label,
  kind: 'text',
  required,
  maxLength: 1024,
});
const decimal = (
  key: string,
  label: string,
  defaultValue?: string,
): CleanParameterField => ({
  ...text(key, label),
  kind: 'decimal',
  maxLength: 128,
  defaultValue,
});
const bool = (
  key: string,
  label: string,
  defaultValue = false,
): CleanParameterField => ({ key, label, kind: 'boolean', defaultValue });
const list = (
  key: string,
  label: string,
  required = true,
): CleanParameterField => ({
  key,
  label,
  kind: 'tags',
  required,
  minItems: required ? 1 : 0,
  maxItems: 128,
  maxLength: 256,
});
const select = (
  key: string,
  label: string,
  values: string[],
  defaultValue: string,
): CleanParameterField => ({
  key,
  label,
  kind: 'select',
  defaultValue,
  options: values.map((value) => ({ label: value, value })),
});

export function auditParameterFields(
  key: string,
  config: Record<string, unknown> = {},
): CleanParameterField[] {
  switch (key) {
    case 'COLUMN_METADATA_COMPLETENESS': {
      return [list('requiredProperties', '必需的元信息属性')];
    }
    case 'CONDITIONAL_REQUIRED_VALIDATION': {
      return [
        list('conditionValues', '触发条件值'),
        bool('blankAsNull', '将空白字符串视为空', true),
      ];
    }
    case 'DATE_FORMAT_VALIDATION': {
      return [
        select(
          'pattern',
          '日期格式',
          [
            'yyyy-MM-dd',
            'yyyy/MM/dd',
            'yyyyMMdd',
            'HH:mm:ss',
            'yyyy-MM-dd HH:mm:ss',
            'yyyy/MM/dd HH:mm:ss',
            "yyyy-MM-dd'T'HH:mm:ss",
          ],
          'yyyy-MM-dd',
        ),
        list('invalidValues', '拒绝的日期占位值', false),
        text('regex', '附加正则（可选）'),
        bool('ignoreNullValue', '忽略空值'),
      ];
    }
    case 'MODEL_STRUCTURE_VALIDATION': {
      return [
        bool('allowExtraColumns', '允许模型外字段'),
        bool('compareType', '比较字段类型', true),
        bool('compareNullable', '比较可空属性', true),
        bool('compareLength', '比较长度及精度', true),
      ];
    }
    case 'NOT_NULL_VALIDATION': {
      return [bool('allowBlank', '允许空白字符串')];
    }
    case 'NUMERIC_OUTLIER_VALIDATION': {
      let details: CleanParameterField[];
      if (config.method === 'INVALID_VALUES')
        details = [list('values', '异常十进制值')];
      else if (config.method === 'IQR')
        details = [
          decimal('multiplier', '四分位距倍数', '1.5'),
          {
            key: 'minSamples',
            label: '最少有效样本数',
            kind: 'integer',
            min: 4,
            max: 1_000_000,
            defaultValue: 4,
          },
        ];
      else
        details = [
          decimal('minValue', '最小合法值'),
          decimal('maxValue', '最大合法值'),
          bool('includeMin', '包含最小值', true),
          bool('includeMax', '包含最大值', true),
        ];
      return [
        select(
          'method',
          '异常值识别方法',
          ['RANGE', 'INVALID_VALUES', 'IQR'],
          'RANGE',
        ),
        ...details,
        bool('ignoreNullValue', '忽略空值'),
      ];
    }
    case 'NUMERIC_RELATION_VALIDATION': {
      return [bool('ignoreNullValue', '忽略空值')];
    }
    case 'PLACEHOLDER_VALIDATION': {
      return [
        { ...list('values', '非法占位值'), allowEmpty: true },
        bool('trim', '比较前去除首尾空白'),
        bool('ignoreCase', '忽略占位值大小写', true),
        bool('ignoreNullValue', '忽略空值'),
      ];
    }
    case 'RECORD_COMPLETENESS_VALIDATION': {
      return [bool('blankAsNull', '将空白字符串视为空', true)];
    }
    case 'SENSITIVE_MASK_VALIDATION': {
      return [
        select(
          'maskType',
          '脱敏格式',
          ['PHONE', 'ID_CARD', 'BANK_CARD', 'CUSTOM'],
          'PHONE',
        ),
        text('maskChar', '掩码字符'),
        ...(config.maskType === 'CUSTOM'
          ? [{ ...text('regex', '合法脱敏正则', true), maxLength: 512 }]
          : []),
        bool('ignoreNullValue', '忽略空值'),
      ];
    }
    case 'STATE_CONSISTENCY_VALIDATION': {
      return [list('conditionValues', '触发状态值')];
    }
    case 'UNIQUE_VALIDATION': {
      return [bool('ignoreNull', '忽略空值', true)];
    }
    default: {
      return [];
    }
  }
}

export function auditParameterDefaults(key: string): Record<string, unknown> {
  const config: Record<string, unknown> = Object.fromEntries(
    auditParameterFields(key)
      .filter((field) => field.defaultValue !== undefined)
      .map((field) => [field.key, field.defaultValue]),
  );
  if (key === 'PLACEHOLDER_VALIDATION') config.values = ['-', 'N/A', 'null'];
  if (key === 'NUMERIC_RELATION_VALIDATION')
    config.conditions = [{ leftField: '', operator: '<=', rightField: '' }];
  if (key === 'CONDITIONAL_REQUIRED_VALIDATION')
    Object.assign(config, {
      conditionColumn: '',
      conditionValues: [],
      requiredColumns: [],
    });
  if (key === 'SENSITIVE_MASK_VALIDATION') config.maskChar = '*';
  if (key === 'STATE_CONSISTENCY_VALIDATION')
    Object.assign(config, {
      conditionColumn: '',
      conditionValues: [],
      assertions: [{ column: '', operator: 'NOT_NULL', valueType: 'TEXT' }],
    });
  if (key === 'MODEL_STRUCTURE_VALIDATION')
    config.expectedColumns = [{ name: '' }];
  if (key === 'COLUMN_METADATA_COMPLETENESS')
    config.requiredProperties = ['name', 'type', 'comment'];
  if (key === 'CROSS_TABLE_CONSISTENCY')
    config.comparisons = [{ leftColumn: '', rightColumn: '' }];
  return config;
}

export function auditReferencedColumns(
  key: string,
  config: Record<string, unknown>,
  selected: string[],
): string[] {
  if (automaticAuditStrategies.has(key)) return [];
  if (key === 'NUMERIC_RELATION_VALIDATION')
    return [
      ...new Set(
        ((config.conditions as Record<string, string>[]) ?? [])
          .flatMap((row) => [row.leftField, row.rightField])
          .filter(
            (column): column is string =>
              typeof column === 'string' && Boolean(column),
          ),
      ),
    ];
  if (key === 'CONDITIONAL_REQUIRED_VALIDATION')
    return [
      ...new Set(
        [
          String(config.conditionColumn ?? ''),
          ...((config.requiredColumns as string[]) ?? []),
        ].filter(
          (column): column is string =>
            typeof column === 'string' && Boolean(column),
        ),
      ),
    ];
  if (key === 'STATE_CONSISTENCY_VALIDATION')
    return [
      ...new Set(
        [
          String(config.conditionColumn ?? ''),
          ...((config.assertions as Record<string, string>[]) ?? []).map(
            (row) => row.column,
          ),
        ].filter(
          (column): column is string =>
            typeof column === 'string' && Boolean(column),
        ),
      ),
    ];
  if (key === 'CROSS_TABLE_CONSISTENCY')
    return [
      ...new Set(
        [
          ...selected,
          String(config.leftColumn ?? ''),
          ...((config.comparisons as Record<string, string>[]) ?? []).map(
            (row) => row.leftColumn,
          ),
        ].filter(
          (column): column is string =>
            typeof column === 'string' && Boolean(column),
        ),
      ),
    ];
  return selected;
}

export function auditParameterIssue(
  key: string,
  config: Record<string, unknown>,
): string | undefined {
  const decimalPattern = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/u;
  for (const field of auditParameterFields(key, config)) {
    const value = config[field.key];
    if (value === undefined || value === null || value === '') {
      if (field.required) return `请填写${field.label}`;
      continue;
    }
    if (
      field.kind === 'decimal' &&
      (typeof value !== 'string' || !decimalPattern.test(value))
    )
      return `${field.label}必须为十进制字符串`;
    if (
      field.kind === 'tags' &&
      (!Array.isArray(value) ||
        value.length < (field.minItems ?? 0) ||
        value.length > (field.maxItems ?? 128) ||
        value.some(
          (item) =>
            typeof item !== 'string' ||
            (!field.allowEmpty && !item.trim()) ||
            item.length > (field.maxLength ?? 256),
        ))
    )
      return `${field.label}列表格式或容量无效`;
  }
  const list = (name: string) =>
    config[name] as Record<string, unknown>[] | undefined;
  if (
    key === 'NUMERIC_RELATION_VALIDATION' &&
    (!list('conditions')?.length ||
      list('conditions')!.length > 32 ||
      list('conditions')!.some(
        (row) =>
          !row.leftField ||
          !row.rightField ||
          !['<', '<=', '<>', '=', '>', '>='].includes(String(row.operator)),
      ))
  )
    return '请配置完整的数值字段比较条件';
  if (
    [
      'CONDITIONAL_REQUIRED_VALIDATION',
      'STATE_CONSISTENCY_VALIDATION',
    ].includes(key) &&
    !config.conditionColumn
  )
    return '请选择触发条件字段';
  if (
    key === 'CONDITIONAL_REQUIRED_VALIDATION' &&
    (!(config.requiredColumns as string[])?.length ||
      (config.requiredColumns as string[]).length > 32)
  )
    return '请选择需要填写的字段';
  if (
    key === 'NUMERIC_OUTLIER_VALIDATION' &&
    (!config.method || config.method === 'RANGE') &&
    !config.minValue &&
    !config.maxValue
  )
    return '请至少配置一个异常值边界';
  if (
    key === 'NUMERIC_OUTLIER_VALIDATION' &&
    config.method === 'INVALID_VALUES' &&
    (config.values as unknown[])?.some(
      (value) => typeof value !== 'string' || !decimalPattern.test(value),
    )
  )
    return '异常值必须为十进制字符串';
  if (
    key === 'SENSITIVE_MASK_VALIDATION' &&
    typeof config.maskChar === 'string' &&
    [...config.maskChar].length !== 1
  )
    return '掩码必须是一个字符';
  if (
    key === 'MODEL_STRUCTURE_VALIDATION' &&
    (!list('expectedColumns')?.length ||
      list('expectedColumns')!.some(
        (row) => typeof row.name !== 'string' || !row.name.trim(),
      ) ||
      new Set(list('expectedColumns')!.map((row) => row.name)).size !==
        list('expectedColumns')!.length)
  )
    return '请填写模型字段且字段名称不能重复';
  if (
    key === 'COLUMN_METADATA_COMPLETENESS' &&
    (config.requiredProperties as string[] | undefined)?.some(
      (field) =>
        ![
          'comment',
          'length',
          'name',
          'nullable',
          'precision',
          'scale',
          'type',
        ].includes(field),
    )
  )
    return '元信息属性只能选择规定字段';
  if (
    key === 'CROSS_TABLE_CONSISTENCY' &&
    (!config.referenceObjectKey ||
      !config.refColumn ||
      !list('comparisons')?.length ||
      list('comparisons')!.some((row) => !row.leftColumn || !row.rightColumn))
  )
    return '请选择引用对象、连接键和完整的跨表比较字段';
  if (key === 'STATE_CONSISTENCY_VALIDATION') {
    const assertions = list('assertions');
    if (!assertions?.length || assertions.length > 32)
      return '请配置1至32条状态断言';
    for (const row of assertions) {
      if (!row.column) return '请选择状态断言的检查字段';
      if (['IN', 'NOT_IN'].includes(String(row.operator))) {
        if (!(row.values as unknown[])?.length) return '请填写状态断言的值集合';
      } else if (
        !['IS_NULL', 'NOT_NULL'].includes(String(row.operator)) &&
        (typeof row.value !== 'string' || !row.value)
      )
        return '请填写状态断言的比较值';
      if (
        row.valueType === 'DECIMAL' &&
        ((row.value !== undefined && !decimalPattern.test(String(row.value))) ||
          (row.values as string[] | undefined)?.some(
            (value) => !decimalPattern.test(value),
          ))
      )
        return '数值断言必须使用十进制字符串';
    }
  }
  return undefined;
}
