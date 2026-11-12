/** 动态脱敏页面：权限码、场景与原因文案（与后端 SafetyMaskSceneEnum、SafetyDynamicMaskRespDTO 一致）。 */

export const DYNAMIC_MASK_QUERY = 'governance:dynamic-mask:query';
export const DYNAMIC_MASK_UPDATE = 'governance:dynamic-mask:update';

/** 场景兜底名称；后端返回 sceneName 时以后端为准 */
export const MASK_SCENE_OPTIONS = [
  { label: '数据查询', value: 'DATA_QUERY' },
  { label: '资产预览', value: 'ASSET_PREVIEW' },
  { label: '集市预览', value: 'MARKET_PREVIEW' },
  { label: '数据库预览', value: 'DATABASE_PREVIEW' },
  { label: '指标查询', value: 'INDICATOR_QUERY' },
  { label: 'ETL 设计预览', value: 'ETL_PREVIEW' },
  { label: '数据导出', value: 'DATA_EXPORT' },
] as const;

/** 可配置的豁免口径（白名单中的 ALL 是通配，不能作为场景口径） */
export const WHITELIST_SCENE_OPTIONS = [
  { label: '查询', value: 'QUERY' },
  { label: '共享', value: 'SHARE' },
  { label: '开放', value: 'OPEN' },
  { label: '导出', value: 'EXPORT' },
] as const;

/** 白名单豁免场景名称 */
const WHITELIST_SCENE_TEXT: Record<string, string> = {
  ACCESS: '访问',
  ALL: '全部',
  EXPORT: '导出',
  OPEN: '开放',
  QUERY: '查询',
  SHARE: '共享',
};

const REASON_TEXT: Record<string, { color: string; label: string }> = {
  RULE: { color: 'orange', label: '命中分类规则' },
  UNCLASSIFIED: { color: 'red', label: '敏感未分类，整体打码' },
  UNTRACEABLE: { color: 'red', label: '来源无法追溯，整体打码' },
  UNVERIFIED: { color: 'red', label: '策略无法核验，整体打码' },
};

export function sceneLabel(code?: string, name?: string): string {
  if (name) return name;
  return (
    MASK_SCENE_OPTIONS.find((item) => item.value === code)?.label ?? code ?? '-'
  );
}

export function whitelistSceneLabel(code?: string): string {
  if (!code) return '-';
  return WHITELIST_SCENE_TEXT[code] ?? code;
}

export function reasonView(reason?: string): { color: string; label: string } {
  if (!reason) return { color: 'default', label: '-' };
  return REASON_TEXT[reason] ?? { color: 'red', label: reason };
}

/** 关闭场景必须写原因；去掉首尾空白后校验 */
export function disableRemarkError(remark: string): string | undefined {
  const value = remark.trim();
  if (!value) return '关闭动态脱敏必须填写原因';
  if (value.length > 500) return '原因不能超过 500 个字符';
  return undefined;
}
