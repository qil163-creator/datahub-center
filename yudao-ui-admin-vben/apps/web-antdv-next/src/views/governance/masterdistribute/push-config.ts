import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

export const PUSH_SCOPE_OPTIONS = [
  { label: '启用数据', value: 'ENABLED' },
  { label: '全部数据', value: 'ALL' },
];
export const PUSH_MODE_OPTIONS = [
  { label: '增量推送', value: 'INCREMENTAL' },
  { label: '全量推送', value: 'FULL' },
];
export const TRANSFORM_OPTIONS = [
  { label: '原值', value: 'ORIGINAL' },
  { label: '标准字典转换', value: 'STANDARD_DICT' },
];
export function pushScopeLabel(value?: string) {
  return PUSH_SCOPE_OPTIONS.find((row) => row.value === value)?.label ?? '-';
}
export function pushModeLabel(value?: string) {
  return PUSH_MODE_OPTIONS.find((row) => row.value === value)?.label ?? '-';
}
export function defaultApiConfig(): GovernanceMasterDistributeApi.ApiConfig {
  return {
    method: 'POST',
    authType: 'TOKEN',
    timeoutSeconds: 30,
    pushScope: 'ENABLED',
    enabledValue: '1',
    pushMode: 'INCREMENTAL',
    retryEnabled: true,
    retryCount: 3,
    retryIntervalMinutes: 5,
    exhaustedAction: 'MANUAL',
    fetchSize: 100,
    mappings: [],
  };
}
export function validateMapping(
  mappings: GovernanceMasterDistributeApi.FieldMapping[],
) {
  const names = new Set<string>();
  for (const row of mappings) {
    if (!row.sourceField || !row.targetField.trim())
      return '请补全模型字段和下游字段';
    if (!row.enabled) continue;
    if (names.has(row.targetField.trim())) return '下游字段名称不能重复';
    names.add(row.targetField.trim());
  }
  return undefined;
}
