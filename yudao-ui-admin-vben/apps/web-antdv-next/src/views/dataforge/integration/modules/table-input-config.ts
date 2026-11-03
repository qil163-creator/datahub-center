import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import dayjs from 'dayjs';

export const TABLE_INPUT_READ_MODES = [
  { label: '全量', value: 'FULL' },
  { label: 'id增量', value: 'ID_INCREMENT' },
  { label: '时间范围增量', value: 'TIME_INCREMENT' },
];
export const TABLE_INPUT_TIME_FORMATS = ['yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'];
export const TABLE_INPUT_BASELINE_TYPES = [
  { label: '固定值', value: 'FIXED' },
  { label: '时间范围', value: 'TIME_RANGE' },
  { label: 'SQL表达式', value: 'SQL' },
];
export const TABLE_INPUT_OPERATORS = ['>', '>=', '<', '<='];

export interface TableInputTimeCondition {
  baselineType?: 'FIXED' | 'SQL' | 'TIME_RANGE';
  baselineValue?: string;
  cursorTime?: string;
  field: string;
  operator?: string;
}

export interface TableInputBatchRead {
  enabled: boolean;
  end?: string;
  field?: string;
  maxRetries: number;
  mode: 'ID' | 'TIME';
  retryIntervalSeconds: number;
  size: string;
  start?: string;
  timeUnit: 'HOUR' | 'MINUTE' | 'SECOND';
}

export function createTableInputBatchRead(): TableInputBatchRead {
  return {
    enabled: false,
    mode: 'ID',
    size: '1',
    timeUnit: 'HOUR',
    maxRetries: 3,
    retryIntervalSeconds: 60,
  };
}

export function isTableInputNumericField(
  field: FoundationDatabaseApi.DatabaseTableColumn,
) {
  return /\b(?:tinyint|smallint|mediumint|int|integer|bigint|int[248]|number|numeric|decimal|dec|float[48]?|double|real|serial|bigserial|smallserial)\b/i.test(
    field.fieldTypeName ?? '',
  );
}

export function isTableInputTimeField(
  field: FoundationDatabaseApi.DatabaseTableColumn,
) {
  return /\b(?:date|datetime[248]?|timestamp(?:tz)?|timestamptz|smalldatetime)\b/i.test(
    field.fieldTypeName ?? '',
  );
}

const decimal = /^[+-]?\d+(?:\.\d+)?$/;

/** 保留十进制字符串，避免大整数起止值经过浮点数。 */
function compareDecimal(left: string, right: string) {
  const parts = (value: string) => {
    const [integer = '0', fraction = ''] = value
      .replace(/^[+-]/, '')
      .split('.');
    const digits = integer.replace(/^0+/, '') || '0';
    return {
      negative: value.startsWith('-') && /[1-9]/.test(digits + fraction),
      digits,
      fraction,
    };
  };
  const a = parts(left);
  const b = parts(right);
  if (a.negative !== b.negative) return a.negative ? -1 : 1;
  const width = Math.max(a.fraction.length, b.fraction.length);
  let result: number;
  if (a.digits.length === b.digits.length) {
    result = `${a.digits}.${a.fraction.padEnd(width, '0')}`.localeCompare(
      `${b.digits}.${b.fraction.padEnd(width, '0')}`,
    );
  } else if (a.digits.length < b.digits.length) {
    result = -1;
  } else {
    result = 1;
  }
  return a.negative ? -result : result;
}

function validTime(value: unknown, withTime: boolean) {
  if (typeof value !== 'string') return false;
  const pattern = withTime
    ? /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/
    : /^\d{4}-\d{2}-\d{2}$/;
  const parsed = dayjs(value);
  return (
    pattern.test(value) &&
    parsed.isValid() &&
    parsed.format(withTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD') === value
  );
}

/** 日期控件只接收当前格式的有效时间，切换分支不覆盖原草稿。 */
export function tableInputPickerValue(value: unknown, withTime: boolean) {
  return validTime(value, withTime) ? String(value) : undefined;
}

export function validateTableInputTimeCondition(
  condition: TableInputTimeCondition,
  fields: FoundationDatabaseApi.DatabaseTableColumn[],
  timeFormat = 'yyyy-MM-dd',
) {
  const messages: string[] = [];
  if (!condition || typeof condition !== 'object')
    return ['时间条件配置无效，请重新编辑'];
  if (!condition.field) messages.push('请选择字段名称');
  else if (
    !fields.some(
      (field) =>
        field.fieldName === condition.field && isTableInputTimeField(field),
    )
  )
    messages.push('请选择当前表中的时间字段');
  if (
    !TABLE_INPUT_BASELINE_TYPES.some(
      (type) => type.value === condition.baselineType,
    )
  )
    messages.push('请选择基准类型');
  if (
    condition.baselineType !== 'TIME_RANGE' &&
    !TABLE_INPUT_OPERATORS.includes(condition.operator ?? '')
  )
    messages.push('请选择运算符');
  const withTime = timeFormat === 'yyyy-MM-dd HH:mm:ss';
  if (
    condition.baselineType === 'FIXED' &&
    !validTime(condition.baselineValue, withTime)
  )
    messages.push('请选择有效的固定时间');
  if (
    condition.baselineType === 'TIME_RANGE' &&
    !validTime(condition.cursorTime, withTime)
  )
    messages.push('请选择有效的游标时间');
  if (condition.baselineType === 'SQL') {
    if (typeof condition.baselineValue !== 'string' || !condition.baselineValue.trim()) messages.push('请输入SQL基准表达式');
    else if (
      /[;]|--|\/\*|\b(?:insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|call)\b/i.test(
        condition.baselineValue,
      )
    )
      messages.push('SQL基准仅支持单条只读表达式');
  }
  return messages;
}

export function validateTableInputConfig(
  config: Record<string, any>,
  fields: FoundationDatabaseApi.DatabaseTableColumn[],
) {
  const messages: string[] = [];
  const mode = config.readMode ?? 'FULL';
  if (!TABLE_INPUT_READ_MODES.some((item) => item.value === mode))
    messages.push('请选择读取模式');
  if (mode === 'ID_INCREMENT') {
    if (!config.incrementColumn) messages.push('请选择id字段');
    else if (
      !fields.some(
        (field) =>
          field.fieldName === config.incrementColumn &&
          isTableInputNumericField(field),
      )
    )
      messages.push('id字段必须是当前表中的数值字段');
    if (
      typeof config.startValue !== 'string' ||
      !decimal.test(config.startValue.trim())
    )
      messages.push('请输入有效的开始值');
  }
  if (mode === 'TIME_INCREMENT') {
    const format = config.timeFormat ?? 'yyyy-MM-dd';
    if (!TABLE_INPUT_TIME_FORMATS.includes(format))
      messages.push('请选择时间格式');
    if (
      !Array.isArray(config.timeConditions) ||
      config.timeConditions.length === 0
    )
      messages.push('请至少新增一个时间增量条件');
    else
      config.timeConditions.forEach(
        (condition: TableInputTimeCondition, index: number) => {
          messages.push(
            ...validateTableInputTimeCondition(condition, fields, format).map(
              (message) => `第${index + 1}个时间条件：${message}`,
            ),
          );
        },
      );
  }
  if (
    typeof config.whereCondition === 'string' &&
    config.whereCondition.length > 256
  )
    messages.push('where条件最多256个字符');
  if (mode === 'FULL' && config.batchRead?.enabled) {
    const batch = config.batchRead;
    if (!['ID', 'TIME'].includes(batch.mode)) messages.push('请选择分批方式');
    if (!batch.field) messages.push('请选择分批字段');
    else if (
      !fields.some(
        (field) =>
          field.fieldName === batch.field &&
          (batch.mode === 'TIME'
            ? isTableInputTimeField(field)
            : isTableInputNumericField(field)),
      )
    )
      messages.push('分批字段类型与分批方式不一致');
    if (batch.mode === 'ID') {
      if (typeof batch.start !== 'string' || !decimal.test(batch.start.trim()))
        messages.push('请输入有效的起始ID值');
      if (
        batch.end &&
        (typeof batch.end !== 'string' || !decimal.test(batch.end.trim()))
      )
        messages.push('请输入有效的结束ID值');
      else if (
        batch.end &&
        typeof batch.start === 'string' &&
        decimal.test(batch.start.trim()) &&
        compareDecimal(batch.start.trim(), batch.end.trim()) >= 0
      )
        messages.push('结束ID值必须大于起始ID值');
      if (
        typeof batch.size !== 'string' ||
        !decimal.test(batch.size.trim()) ||
        compareDecimal(batch.size.trim(), '0') <= 0
      )
        messages.push('每批ID跨度必须大于0');
    }
    if (batch.mode === 'TIME') {
      if (!validTime(batch.start, true)) messages.push('请选择有效的起始时间');
      if (batch.end && !validTime(batch.end, true))
        messages.push('请选择有效的结束时间');
      else if (
        batch.end &&
        validTime(batch.start, true) &&
        dayjs(batch.end).valueOf() <= dayjs(batch.start).valueOf()
      )
        messages.push('结束时间必须晚于起始时间');
      if (!['HOUR', 'MINUTE', 'SECOND'].includes(batch.timeUnit))
        messages.push('请选择时间单位');
      if (
        typeof batch.size !== 'string' ||
        !/^\d+$/.test(batch.size) ||
        compareDecimal(batch.size, '0') <= 0
      )
        messages.push('每批时间跨度必须为正整数');
    }
    if (
      !Number.isInteger(batch.maxRetries) ||
      batch.maxRetries < 0 ||
      batch.maxRetries > 100
    )
      messages.push('最大重试次数必须为0至100的整数');
    if (
      !Number.isInteger(batch.retryIntervalSeconds) ||
      batch.retryIntervalSeconds < 1 ||
      batch.retryIntervalSeconds > 86_400
    )
      messages.push('读取重试间隔必须为1至86400秒的整数');
  }
  return messages;
}
