/**
 * Excel 导入的手工列映射与查重设置。
 *
 * 映射以“Excel 表头 → 模板字段码”表示，空字符串表示忽略该列；服务端以手工映射优先，
 * 未出现在映射里的表头仍按名称自动匹配。
 */

export interface HeaderMapping {
  fieldCode?: string;
  header: string;
}

/** 预检结果中的表头对应关系 → 可编辑的映射 */
export function toMapping(headers: HeaderMapping[] | undefined): Record<string, string> {
  const mapping: Record<string, string> = {};
  for (const item of headers ?? []) {
    mapping[item.header] = item.fieldCode ?? '';
  }
  return mapping;
}

/** 被多个表头同时映射的字段码（服务端会拒绝，前端先提示） */
export function duplicateTargets(mapping: Record<string, string>): string[] {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const fieldCode of Object.values(mapping)) {
    if (!fieldCode) continue;
    if (seen.has(fieldCode)) duplicates.add(fieldCode);
    seen.add(fieldCode);
  }
  return [...duplicates];
}

/** 导入结果提示文本 */
export function describeImportResult(resp: {
  addedCount?: number;
  draftRowCount: number;
  skippedCount?: number;
  updatedCount?: number;
}, replaced: boolean): string {
  const parts = [`${replaced ? '已替换为' : '新增'} ${resp.addedCount ?? 0} 行`];
  if (resp.updatedCount) parts.push(`覆盖 ${resp.updatedCount} 行`);
  if (resp.skippedCount) parts.push(`跳过重复 ${resp.skippedCount} 行`);
  return `导入成功：${parts.join('，')}（当前共 ${resp.draftRowCount} 行）`;
}
