export interface ReferenceField {
  name: string;
  type?: null | string;
  alias?: string;
  comment?: string;
  jsonPath?: string;
  length?: null | number | string;
  precision?: null | number | string;
  format?: string;
  index?: number;
  key?: string;
}

export const referenceFieldTypes = [
  'BigNumber',
  'Binary',
  'Boolean',
  'Date',
  'Integer',
  'InternetAddress',
  'Number',
  'String',
  'Timestamp',
].map((value) => ({ label: value, value }));

/** 只加入已确认的字段；线上 addAll 与 clearAndAddAll 均重置为全部字段，addNewOnly 排除当前与删除字段。 */
export function resolveReferenceFields(
  current: readonly ReferenceField[],
  removed: readonly string[],
  upstream: readonly { dataType?: null | string; name?: string }[],
  mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll',
): ReferenceField[] {
  const known = new Set([...current.map((field) => field.name), ...removed]);
  const incoming = upstream.filter(
    (field) => field.name && (mode !== 'addNewOnly' || !known.has(field.name)),
  );
  const added = incoming.map((field) => ({
    name: field.name!,
    ...(field.dataType ? { type: field.dataType } : {}),
  }));
  return mode === 'addNewOnly'
    ? [...current.map((field) => ({ ...field })), ...added]
    : added;
}

export function moveReferenceField<T>(
  rows: readonly T[],
  from: number,
  to: number,
): T[] {
  const result = [...rows];
  if (from < 0 || to < 0 || from >= rows.length || to >= rows.length)
    return result;
  const [row] = result.splice(from, 1);
  result.splice(to, 0, row!);
  return result;
}

export function validateReferenceFields(
  fields: readonly ReferenceField[],
  requireType = true,
): string[] {
  const issues: string[] = [];
  if (fields.length === 0) issues.push('请新增或获取属性字段');
  const outputs = new Set<string>();
  for (const [index, field] of fields.entries()) {
    if (!field.name?.trim()) issues.push(`第${index + 1}行字段名称不能为空`);
    if (requireType && !field.type?.trim())
      issues.push(`第${index + 1}行字段类型尚未确定，请手动确认`);
    const output = field.alias?.trim() || field.name?.trim();
    if (outputs.has(output)) issues.push(`输出字段名称重复：${output}`);
    outputs.add(output);
    for (const key of ['length', 'precision'] as const) {
      const value = field[key];
      if (
        value !== undefined &&
        value !== null &&
        value !== '' &&
        !/^\d+$/u.test(String(value))
      )
        issues.push(
          `第${index + 1}行字段${key === 'length' ? '长度' : '精度'}应为非负整数`,
        );
    }
  }
  return issues;
}

/** 参数可携带非敏感客户端选项；认证材料必须留在平台连接中。 */
export function parseReferenceParameters(
  raw: unknown,
): Record<string, unknown> {
  const value: unknown =
    typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw ?? {});
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('配置参数必须为JSON对象');
  const inspect = (item: unknown, depth = 0) => {
    if (depth > 12) throw new Error('配置参数嵌套过深');
    if (!item || typeof item !== 'object') return;
    for (const [key, child] of Object.entries(item)) {
      if (
        /(?:password|passwd|secret|token|credential|username|jaas)/iu.test(key)
      )
        throw new Error(
          '认证参数请在平台数据连接中配置，节点不保存账号、密码或密钥',
        );
      inspect(child, depth + 1);
    }
  };
  inspect(value);
  return value as Record<string, unknown>;
}
