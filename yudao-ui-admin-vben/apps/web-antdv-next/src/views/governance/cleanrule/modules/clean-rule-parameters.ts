/** 与质量组件清洗函数契约一致的参数表单。 */
export interface CleanParameterField {
  key: string;
  label: string;
  kind:
    | 'boolean'
    | 'decimal'
    | 'integer'
    | 'mapping'
    | 'select'
    | 'sort'
    | 'tags'
    | 'text';
  defaultValue?: boolean | null | number | string | string[];
  required?: boolean;
  allowEmpty?: boolean;
  allowNull?: boolean;
  minItems?: number;
  maxItems?: number;
  min?: number;
  max?: number;
  maxLength?: number;
  options?: { label: string; value: number | string }[];
}

export interface CleanMappingItem {
  value: string;
  name: string;
}
export interface CleanSortItem {
  columns: string;
  type: number;
  sort: number;
}

const choice = (
  key: string,
  label: string,
  labels: string[],
  defaultValue: number,
  start = 1,
): CleanParameterField => ({
  key,
  label,
  kind: 'select',
  defaultValue,
  options: labels.map((text, index) => ({ label: text, value: index + start })),
});
const regexFields: CleanParameterField[] = [
  {
    key: 'regex',
    label: '匹配正则',
    kind: 'text',
    required: true,
    maxLength: 512,
  },
  {
    key: 'replacement',
    label: '替换内容',
    kind: 'text',
    allowEmpty: true,
    maxLength: 1024,
  },
];

const text = (
  key: string,
  label: string,
  options: Partial<CleanParameterField> = {},
): CleanParameterField => ({
  key,
  label,
  kind: 'text',
  maxLength: 1024,
  ...options,
});
const list = (
  key: string,
  label: string,
  options: Partial<CleanParameterField> = {},
): CleanParameterField => ({
  key,
  label,
  kind: 'tags',
  maxLength: 128,
  maxItems: 128,
  ...options,
});
const selection = (
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
const bool = (
  key: string,
  label: string,
  defaultValue: boolean,
  allowNull = false,
): CleanParameterField => ({
  key,
  label,
  kind: 'boolean',
  defaultValue,
  allowNull,
});
const scale: CleanParameterField = {
  key: 'scale',
  label: '保留小数位数',
  kind: 'integer',
  min: 0,
  max: 12,
  defaultValue: 2,
};
const inputFormats = list('inputFormats', '输入日期格式', {
  maxItems: 16,
  maxLength: 64,
});
const outputFormat = text('outputFormat', '输出日期格式', {
  maxLength: 64,
  defaultValue: 'yyyy-MM-dd HH:mm:ss',
});
const invalid = selection(
  'onInvalid',
  '无法转换时',
  ['KEEP', 'NULL', 'ERROR'],
  'KEEP',
);
const booleanValues = [
  list('trueValues', '真值集合', { defaultValue: ['true', '1', '是', 'yes'] }),
  list('falseValues', '假值集合', { defaultValue: ['false', '0', '否', 'no'] }),
  bool('ignoreCase', '忽略大小写', true),
];

const fields: Record<string, CleanParameterField[]> = {
  STRING_TO_NUMBER: [
    selection('targetType', '目标类型', ['DECIMAL', 'INTEGER'], 'DECIMAL'),
    invalid,
  ],
  STRING_TO_DATE: [
    selection('targetType', '目标日期类型', ['DATE', 'DATETIME'], 'DATE'),
    inputFormats,
    invalid,
  ],
  TO_BOOLEAN: [...booleanValues, invalid],
  ZERO_PAD: [
    {
      key: 'length',
      label: '补齐后长度',
      kind: 'integer',
      required: true,
      min: 1,
      max: 10_000,
    },
    selection('direction', '补零方向', ['LEFT', 'RIGHT'], 'LEFT'),
  ],
  NUMBER_FILL_NULL: [
    selection('mode', '空值填充方式', ['FIXED', 'MEAN'], 'FIXED'),
    text('value', '固定数值', {
      kind: 'decimal',
      defaultValue: '0',
      maxLength: 128,
    }),
    scale,
  ],
  STRING_FILL_NULL: [
    text('value', '空值填充值', { defaultValue: '未知', allowEmpty: true }),
  ],
  BOOLEAN_FILL_NULL: [bool('value', '空值填充布尔值', false)],
  REMOVE_EMPTY_ROW: [],
  UNIT_CONVERSION: [
    text('factor', '换算系数', {
      kind: 'decimal',
      required: true,
      maxLength: 128,
    }),
    text('offset', '换算偏移量', {
      kind: 'decimal',
      defaultValue: '0',
      maxLength: 128,
    }),
    scale,
  ],
  CAPITALIZE_WORDS: [],
  PK_DEDUP_LATEST: [
    text('timeField', '保留最新记录的时间字段', {
      required: true,
      maxLength: 128,
    }),
    inputFormats,
  ],
  DEDUP_BY_PRIORITY: [
    text('priorityField', '优先级字段', { required: true, maxLength: 128 }),
    list('priorityValues', '优先级值（前项优先）', {
      required: true,
      minItems: 1,
    }),
    text('timeField', '优先级相同时的时间字段', { maxLength: 128 }),
    inputFormats,
  ],
  DEDUP_PREFER_NON_NULL: [
    list('preferredFields', '优先非空的字段', {
      required: true,
      minItems: 1,
      maxItems: 32,
    }),
    text('timeField', '完整度相同时的时间字段', { maxLength: 128 }),
    inputFormats,
  ],
  INVALID_NUMBER_REPLACE: [
    text('min', '合法数值下界', { kind: 'decimal', maxLength: 128 }),
    text('max', '合法数值上界', { kind: 'decimal', maxLength: 128 }),
    list('invalidValues', '异常数值标记'),
    text('replacement', '替换数值', {
      kind: 'decimal',
      maxLength: 128,
      allowNull: true,
    }),
  ],
  INVALID_ENUM_REPLACE: [
    list('validValues', '合法枚举值', {
      required: true,
      minItems: 1,
      maxItems: 1000,
    }),
    bool('ignoreCase', '忽略大小写', false),
    text('replacement', '非法值替换内容', {
      allowEmpty: true,
      allowNull: true,
    }),
  ],
  INVALID_DATE_REPLACE: [
    inputFormats,
    text('replacement', '无效日期替换值', { allowNull: true }),
  ],
  INVALID_BOOLEAN_REPLACE: [
    ...booleanValues,
    bool('replacement', '非法布尔值替换为', false, true),
  ],
  KEYWORD_MASK: [
    list('keywords', '需要掩码的关键词', { required: true, minItems: 1 }),
    text('mask', '掩码字符', { defaultValue: '*', maxLength: 2 }),
    bool('ignoreCase', '忽略大小写', false),
  ],
  SENSITIVE_WORD_REPLACE: [
    list('keywords', '敏感词', { required: true, minItems: 1 }),
    text('replacement', '敏感词替换为', {
      defaultValue: '违规内容',
      allowEmpty: true,
    }),
    bool('ignoreCase', '忽略大小写', false),
  ],
  FIX_FUTURE_DATE: [
    selection(
      'replacement',
      '未来日期处理',
      ['CURRENT', 'BOUNDARY'],
      'CURRENT',
    ),
    text('boundary', '指定日期边界'),
    inputFormats,
    outputFormat,
  ],
  CLAMP_FUTURE_OFFSET: [
    {
      key: 'amount',
      label: '允许的未来偏移量',
      kind: 'integer',
      required: true,
      min: 0,
      max: 100_000,
    },
    selection(
      'unit',
      '偏移单位',
      ['SECOND', 'MINUTE', 'HOUR', 'DAY', 'MONTH', 'YEAR'],
      'DAY',
    ),
    inputFormats,
    outputFormat,
  ],
  TIME_FILL_NULL: [
    selection('mode', '空时间填充方式', ['CURRENT', 'FIXED'], 'CURRENT'),
    text('value', '固定填充时间'),
    outputFormat,
  ],
  FIX_HISTORICAL_DATE: [
    text('boundary', '最早允许日期', { required: true }),
    inputFormats,
    outputFormat,
  ],
  ADD_PREFIX_SUFFIX: [
    choice(
      'handleType',
      '处理方式',
      ['添加前缀', '添加后缀', '移除前缀', '移除后缀'],
      1,
    ),
    {
      key: 'stringValue',
      label: '前缀或后缀',
      kind: 'text',
      required: true,
      maxLength: 128,
    },
  ],
  CHECK_EXPIRATION: [
    choice('dataRange', '截止方式', ['相对当前日期', '固定日期'], 0, 0),
    choice('dataRangeType', '相对日期单位', ['天', '月', '年'], 1),
    {
      key: 'dataRangeValue',
      label: '相对日期数量',
      kind: 'integer',
      defaultValue: 30,
      min: 0,
      max: 100_000,
    },
    choice('handleType', '过期记录处理', ['更新字段', '删除记录'], 0, 0),
    {
      key: 'handleColumns',
      label: '更新字段',
      kind: 'text',
      allowEmpty: true,
      maxLength: 128,
    },
    {
      key: 'handleValue',
      label: '更新值',
      kind: 'text',
      allowEmpty: true,
      maxLength: 256,
    },
  ],
  DATE_FILL_NULL: [
    {
      key: 'fillType',
      label: '填充方式',
      kind: 'select',
      defaultValue: 'CURRENT',
      options: [
        { label: '当前时间', value: 'CURRENT' },
        { label: '昨天', value: 'YESTERDAY' },
        { label: '固定值', value: 'FIXED' },
      ],
    },
    {
      key: 'format',
      label: '日期格式',
      kind: 'text',
      defaultValue: 'yyyy-MM-dd HH:mm:ss',
      maxLength: 64,
    },
    {
      key: 'value',
      label: '固定填充值',
      kind: 'text',
      allowEmpty: true,
      maxLength: 128,
    },
  ],
  DATE_FORMAT_STD: [
    {
      key: 'targetFormat',
      label: '目标日期格式',
      kind: 'text',
      defaultValue: 'yyyy-MM-dd',
      maxLength: 64,
    },
    { key: 'inputFormats', label: '输入日期格式', kind: 'tags', maxLength: 64 },
  ],
  FIX_TO_PRECISION: [
    {
      key: 'scale',
      label: '保留小数位数',
      kind: 'integer',
      defaultValue: 2,
      min: 0,
      max: 12,
    },
  ],
  KEEP_LATEST_OR_FIRST: [
    { key: 'stringValue', label: '保留记录排序', kind: 'sort' },
  ],
  MENU_CUSTOM: [
    { key: 'stringValue', label: '枚举映射', kind: 'mapping', required: true },
  ],
  PHONE_FORMAT_STD: [],
  PK_DEDUP_FIRST: [],
  REMOVE_EMPTY_COMBINATION: [],
  REMOVE_WHITESPACE: [
    choice('handleType', '去空格范围', ['首尾空格', '所有空格'], 1),
  ],
  SIMPLE_REPLACE: regexFields,
  STRING_SUBSTR: [
    {
      key: 'maxLength',
      label: '最大长度',
      kind: 'integer',
      required: true,
      min: 1,
      max: 10_000,
    },
    choice('direction', '截取方向', ['保留开头', '保留末尾'], 1),
  ],
  TO_LOWERCASE: [],
  TO_UPPERCASE: [],
  VALUE_REPLACE: regexFields,
  WITHIN_BOUNDARY: [
    {
      key: 'min',
      label: '最小值',
      kind: 'decimal',
      defaultValue: '0',
      maxLength: 128,
    },
    {
      key: 'max',
      label: '最大值',
      kind: 'decimal',
      defaultValue: '0',
      maxLength: 128,
    },
    choice(
      'handleType',
      '越界处理',
      ['调整到最近边界', '统一为最小值', '统一为最大值'],
      1,
    ),
  ],
};

export function cleanParameterStrategyKeys(): string[] {
  return Object.keys(fields);
}

export function cleanParameterFields(
  strategyKey?: string,
  config: Record<string, unknown> = {},
) {
  const schema =
    strategyKey && Object.hasOwn(fields, strategyKey)
      ? fields[strategyKey]
      : undefined;
  return schema?.map((field): CleanParameterField => {
    if (strategyKey === 'FIX_FUTURE_DATE' && field.key === 'boundary')
      return { ...field, required: config.replacement === 'BOUNDARY' };
    if (strategyKey === 'TIME_FILL_NULL' && field.key === 'value')
      return { ...field, required: config.mode === 'FIXED' };
    if (strategyKey === 'DATE_FILL_NULL' && field.key === 'value') {
      return {
        ...field,
        required: config.fillType === 'FIXED',
        allowEmpty: config.fillType !== 'FIXED',
      };
    }
    if (
      strategyKey === 'CHECK_EXPIRATION' &&
      field.key === 'dataRangeValue' &&
      String(config.dataRange ?? 0) === '1'
    ) {
      return {
        key: field.key,
        label: '截止日期',
        kind: 'text',
        required: true,
        maxLength: 32,
      };
    }
    return field;
  });
}

/** Preserve absent parameters and unknown historical keys until explicit edits. */
export function replaceCleanParameter(
  config: null | Record<string, unknown> | undefined,
  key: string,
  value: unknown,
) {
  const result = Object.fromEntries(
    Object.entries(config ?? {}).filter(([name]) => name !== key),
  );
  if (value !== undefined) result[key] = value;
  if (key === 'scale') delete result.stringValue; // Legacy precision alias.
  return result;
}

export function cleanParameterValue(
  config: null | Record<string, unknown> | undefined,
  key: string,
  strategyKey?: string,
): unknown {
  return strategyKey === 'FIX_TO_PRECISION' && key === 'scale'
    ? (config?.scale ?? config?.stringValue)
    : config?.[key];
}

/** Check form shapes and bounds; Java regex, date formats and exact decimal ordering remain server validated. */
export function cleanParameterIssues(
  strategyKey: string | undefined,
  config: Record<string, unknown>,
): string[] {
  const schema = cleanParameterFields(strategyKey, config);
  if (!schema)
    return Object.keys(config).length === 0
      ? []
      : ['历史或未知策略只能保留空参数'];
  const errors: string[] = [];
  const allowed = new Set(schema.map((field) => field.key));
  if (strategyKey === 'FIX_TO_PRECISION') allowed.add('stringValue');
  if (Object.keys(config).some((key) => !allowed.has(key)))
    errors.push('参数包含未知字段；字段绑定应在 ETL 节点配置');
  for (const field of schema) {
    const value = cleanParameterValue(config, field.key, strategyKey);
    const add = (text: string) => errors.push(`${field.label}：${text}`);
    if (value === undefined) {
      if (field.required) add('请填写');
      continue;
    }
    if (value === null) {
      if (!field.allowNull) add('不能设置为 null');
      continue;
    }
    if (field.kind === 'boolean') {
      if (typeof value !== 'boolean') add('请选择布尔值');
    } else if (field.kind === 'integer') {
      const numeric =
        typeof value === 'string' && /^[+-]?\d{1,10}$/.test(value)
          ? Number(value)
          : value;
      if (
        typeof numeric !== 'number' ||
        !Number.isInteger(numeric) ||
        numeric < field.min! ||
        numeric > field.max!
      )
        add(`请输入 ${field.min} 至 ${field.max} 的整数`);
    } else if (field.kind === 'select') {
      if (
        !field.options?.some((option) => String(option.value) === String(value))
      )
        add('请选择有效选项');
    } else if (field.kind === 'text' || field.kind === 'decimal') {
      if (
        typeof value !== 'string' ||
        value.length > field.maxLength! ||
        (!field.allowEmpty && !value.trim())
      )
        add('文本类型或长度无效');
      else if (
        field.kind === 'decimal' &&
        !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
      )
        add('请输入十进制字符串');
    } else if (field.kind === 'tags') {
      if (
        !Array.isArray(value) ||
        value.length > (field.maxItems ?? 16) ||
        value.length < (field.minItems ?? 0) ||
        value.some(
          (item) =>
            typeof item !== 'string' ||
            !item.trim() ||
            item.length > (field.maxLength ?? 64),
        )
      )
        add(
          `请输入 ${field.minItems ?? 0} 至 ${field.maxItems ?? 16} 项，每项最多 ${field.maxLength ?? 64} 字符`,
        );
    } else if (field.kind === 'mapping') {
      if (
        !Array.isArray(value) ||
        value.length === 0 ||
        value.length > 1000 ||
        value.some(
          (item) =>
            !item ||
            typeof item !== 'object' ||
            Object.keys(item).toSorted().join(',') !== 'name,value' ||
            typeof item.value !== 'string' ||
            item.value.length > 128 ||
            typeof item.name !== 'string' ||
            item.name.length > 256,
        ) ||
        new Set(value.map((item) => item.value)).size !== value.length
      )
        add(
          '需要 1 至 1000 个唯一原值映射，原值最多 128 字符、目标值最多 256 字符',
        );
    } else if (
      !Array.isArray(value) ||
      value.length > 32 ||
      value.some(
        (item) =>
          !item ||
          typeof item !== 'object' ||
          Object.keys(item).toSorted().join(',') !== 'columns,sort,type' ||
          typeof item.columns !== 'string' ||
          !item.columns.trim() ||
          item.columns.length > 128 ||
          ![0, 1].includes(item.type) ||
          !Number.isInteger(item.sort) ||
          Math.abs(item.sort) > 100_000,
      ) ||
      new Set(value.map((item) => item.columns)).size !== value.length
    )
      add('最多 32 个唯一排序字段，优先级须为 -100000 至 100000 的整数');
  }
  if (new TextEncoder().encode(JSON.stringify(config)).length > 64 * 1024)
    errors.push('清洗函数参数不能超过 64 KiB');
  if (
    strategyKey === 'KEYWORD_MASK' &&
    typeof config.mask === 'string' &&
    [...config.mask].length !== 1
  )
    errors.push('掩码字符：必须为一个 Unicode 字符');
  return errors;
}
