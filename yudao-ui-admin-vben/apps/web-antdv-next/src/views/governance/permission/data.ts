export const TABLE_PERMISSION_OPTIONS = [
  { label: '禁止访问', value: 0, color: 'error' as const },
  { label: '允许读取', value: 1, color: 'warning' as const },
  { label: '允许读取（历史读写）', value: 2, color: 'success' as const },
];

export const FIELD_PERMISSION_OPTIONS = [
  { label: '禁止访问', value: 0, color: 'error' as const },
  { label: '明文访问', value: 1, color: 'success' as const },
  { label: '脱敏访问', value: 2, color: 'warning' as const },
];

export function tablePermissionLabel(type?: number) {
  return (
    TABLE_PERMISSION_OPTIONS.find((item) => item.value === type)?.label ??
    '未配置'
  );
}

/** 预检传输保留精确文本，数字必须由用户显式以字符串给出。 */
export function parseWriteParameters(text: string): (null | string)[] {
  const value: unknown = JSON.parse(text);
  if (
    !Array.isArray(value) ||
    value.length > 5000 ||
    value.some(
      (item) =>
        item !== null && (typeof item !== 'string' || item.length > 20_000),
    )
  ) {
    throw new Error('参数必须为字符串或 null 的 JSON 数组');
  }
  return value;
}

export function fieldPermissionLabel(type?: number) {
  return (
    FIELD_PERMISSION_OPTIONS.find((item) => item.value === type)?.label ??
    '未配置'
  );
}

export function tablePermissionColor(type?: number) {
  return (
    TABLE_PERMISSION_OPTIONS.find((item) => item.value === type)?.color ??
    'default'
  );
}

export function fieldPermissionColor(type?: number) {
  return (
    FIELD_PERMISSION_OPTIONS.find((item) => item.value === type)?.color ??
    'default'
  );
}

export function configKey(
  databaseId: number | string,
  schemaName: string,
  tableName: string,
  roleId: number | string,
) {
  return JSON.stringify([
    String(databaseId),
    schemaName,
    tableName,
    String(roleId),
  ]);
}

export function isConfigForTable(
  key: string,
  databaseId: number | string,
  schemaName: string,
  tableName: string,
) {
  try {
    const parts: unknown = JSON.parse(key);
    return (
      Array.isArray(parts) &&
      parts.length === 4 &&
      parts[0] === String(databaseId) &&
      parts[1] === schemaName &&
      parts[2] === tableName
    );
  } catch {
    return false;
  }
}
