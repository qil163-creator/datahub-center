import type { DataforgeEtlApi } from '#/api/dataforge/etl';

export const PARAMETER_TYPES = [
  { label: '字符串', value: 'STRING' },
  { label: '整数', value: 'INTEGER' },
  { label: '精确小数', value: 'DECIMAL' },
  { label: '布尔值', value: 'BOOLEAN' },
];
const namePattern = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const integerPattern = /^[+-]?\d+$/;
const decimalPattern = /^[+-]?\d+(?:\.\d+)?$/;

/** Compare bounded plain decimals by sign and decimal digits, without floating point conversion. */
export function compareParameterNumbers(left: string, right: string): number {
  const normalize = (text: string) => {
    const negative = text.startsWith('-');
    const [integer = '0', fraction = ''] = text.replace(/^[+-]/, '').split('.');
    const whole = integer.replace(/^0+(?=\d)/, '');
    const decimal = fraction.replace(/0+$/, '');
    return {
      negative: negative && (whole !== '0' || decimal !== ''),
      whole,
      decimal,
    };
  };
  const a = normalize(left);
  const b = normalize(right);
  if (a.negative !== b.negative) return a.negative ? -1 : 1;
  const sign = a.negative ? -1 : 1;
  if (a.whole.length !== b.whole.length)
    return (a.whole.length > b.whole.length ? 1 : -1) * sign;
  if (a.whole !== b.whole) return (a.whole > b.whole ? 1 : -1) * sign;
  const length = Math.max(a.decimal.length, b.decimal.length);
  const x = a.decimal.padEnd(length, '0');
  const y = b.decimal.padEnd(length, '0');
  return x === y ? 0 : (x > y ? 1 : -1) * sign;
}
function numericValid(value: unknown, integer: boolean) {
  return (
    typeof value === 'string' &&
    value.length <= 128 &&
    (integer ? integerPattern : decimalPattern).test(value)
  );
}
export function parameterValueIssue(
  definition: DataforgeEtlApi.ParamItem,
  value: string | undefined,
): string | undefined {
  const type = definition.type ?? 'STRING';
  if (value === undefined)
    return definition.required ? '必填参数不能为空' : undefined;
  if (typeof value !== 'string') return '参数值必须为字符串';
  if (value.includes('${')) return '参数值不能包含占位符宏文本';
  if (definition.required && !value.trim()) return '必填参数不能为空';
  if (value.length > 4096) return '参数值最多 4096 个字符';
  if (type === 'STRING') {
    if (definition.required && !value.trim()) return '必填参数不能为空';
    if (
      definition.minLength !== undefined &&
      definition.minLength !== null &&
      value.length < definition.minLength
    )
      return '小于最小长度';
    if (
      definition.maxLength !== undefined &&
      definition.maxLength !== null &&
      value.length > definition.maxLength
    )
      return '超过最大长度';
  } else if (type === 'BOOLEAN') {
    if (!/^(?:true|false)$/i.test(value)) return '布尔值只能为 true 或 false';
  } else if (type === 'DECIMAL' || type === 'INTEGER') {
    if (!numericValid(value, type === 'INTEGER'))
      return type === 'INTEGER'
        ? '请输入最多 128 字符的十进制整数'
        : '请输入最多 128 字符的普通十进制小数，不支持指数';
    if (
      definition.min !== undefined &&
      definition.min !== null &&
      numericValid(definition.min, type === 'INTEGER') &&
      compareParameterNumbers(value, definition.min) < 0
    )
      return '小于最小值';
    if (
      definition.max !== undefined &&
      definition.max !== null &&
      numericValid(definition.max, type === 'INTEGER') &&
      compareParameterNumbers(value, definition.max) > 0
    )
      return '超过最大值';
  } else return '参数类型不受支持';
  return undefined;
}
export function parameterDefinitionIssues(
  definitions: DataforgeEtlApi.ParamItem[],
): string[] {
  const issues: string[] = [];
  if (definitions.length > 100) issues.push('最多定义 100 个参数');
  const names = new Set<string>();
  for (const [index, item] of definitions.entries()) {
    const label = item.name || `第 ${index + 1} 个参数`;
    const add = (text: string) => issues.push(`${label}：${text}`);
    if (!item.name || !namePattern.test(item.name))
      add('名称须以字母或下划线开头，最多 64 个字母、数字或下划线');
    else if (names.has(item.name)) add('参数名重复');
    if (item.name) names.add(item.name);
    const type = item.type ?? 'STRING';
    if (!PARAMETER_TYPES.some((option) => option.value === type))
      add('类型无效');
    const numeric = type === 'INTEGER' || type === 'DECIMAL';
    for (const key of ['min', 'max'] as const) {
      if (
        item[key] !== undefined &&
        item[key] !== null &&
        (!numeric || !numericValid(item[key], type === 'INTEGER'))
      )
        add(`${key} 仅支持对应数值类型的普通十进制字符串`);
    }
    if (
      numeric &&
      item.min !== undefined &&
      item.min !== null &&
      item.max !== undefined &&
      item.max !== null &&
      numericValid(item.min, type === 'INTEGER') &&
      numericValid(item.max, type === 'INTEGER') &&
      compareParameterNumbers(item.min, item.max) > 0
    )
      add('最小值不能大于最大值');
    for (const key of ['minLength', 'maxLength'] as const) {
      const value = item[key];
      if (
        value !== undefined &&
        value !== null &&
        (type !== 'STRING' ||
          !Number.isInteger(value) ||
          value < 0 ||
          value > 4096)
      )
        add(`${key} 仅支持字符串的 0 至 4096 整数长度`);
    }
    if (
      item.minLength !== undefined &&
      item.minLength !== null &&
      item.maxLength !== undefined &&
      item.maxLength !== null &&
      item.minLength > item.maxLength
    )
      add('最小长度不能大于最大长度');
    if (item.defaultValue !== undefined && item.defaultValue !== null) {
      const issue = parameterValueIssue(item, item.defaultValue);
      if (issue) add(`默认值${issue}`);
    }
  }
  return issues;
}
export function initialParameterValues(
  definitions: DataforgeEtlApi.ParamItem[],
) {
  return Object.fromEntries(
    definitions
      .filter(
        (item) =>
          item.name &&
          item.defaultValue !== undefined &&
          item.defaultValue !== null,
      )
      .map((item) => [item.name!, item.defaultValue!]),
  );
}
export function resolveParameterValues(
  definitions: DataforgeEtlApi.ParamItem[],
  values: Record<string, string>,
) {
  const issues = parameterDefinitionIssues(definitions);
  const parameters: Record<string, string> = Object.create(null);
  for (const item of definitions) {
    if (!item.name) continue;
    const value = Object.hasOwn(values, item.name)
      ? values[item.name]
      : (item.defaultValue ?? undefined);
    const issue = parameterValueIssue(item, value);
    if (issue) issues.push(`${item.name}：${issue}`);
    if (typeof value === 'string')
      parameters[item.name] =
        item.type === 'BOOLEAN' ? value.toLowerCase() : value;
  }
  for (const key of Object.keys(values))
    if (!definitions.some((item) => item.name === key))
      issues.push(`${key}：未定义参数`);
  return { parameters, issues };
}
export function parameterConstraintText(item: DataforgeEtlApi.ParamItem) {
  const parts = [item.type ?? 'STRING', item.required ? '必填' : '可选'];
  if (item.min !== undefined && item.min !== null)
    parts.push(`最小值 ${item.min}`);
  if (item.max !== undefined && item.max !== null)
    parts.push(`最大值 ${item.max}`);
  if (item.minLength !== undefined && item.minLength !== null)
    parts.push(`最短 ${item.minLength}`);
  if (item.maxLength !== undefined && item.maxLength !== null)
    parts.push(`最长 ${item.maxLength}`);
  return parts.join(' · ');
}
