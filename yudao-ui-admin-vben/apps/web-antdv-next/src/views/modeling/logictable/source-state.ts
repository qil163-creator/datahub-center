import type { TableModelApi } from '#/api/modeling/tablemodel';

export interface SourceTableSelection {
  tableName: string;
  comment: string;
  targetName: string;
  targetComment: string;
  tableCase: number;
  schemaName?: string;
  savedId?: TableModelApi.Id;
  error?: string;
  uncertain?: boolean;
}

export function sourceTableKey(row: {
  schemaName?: string;
  tableName: string;
}) {
  return JSON.stringify([row.schemaName || '', row.tableName]);
}

export function sourceTargetName(
  tableName: string,
  tableCase = 1,
  prefix = 'ODS_',
) {
  const full = `${prefix}${tableName}`;
  return tableCase === 2 ? full.toLowerCase() : full.toUpperCase();
}

export function validateSourceSelections(rows: SourceTableSelection[]) {
  if (rows.length === 0) return '请至少选择一张源表';
  const names = new Set<string>();
  for (const row of rows) {
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(row.targetName))
      return '目标逻辑表名须以字母开头，仅包含字母、数字和下划线';
    if (!row.targetComment.trim()) return '请填写目标中文表名';
    const normalized = row.targetName.toLowerCase();
    if (names.has(normalized)) return `目标逻辑表名 ${row.targetName} 重复`;
    names.add(normalized);
  }
  return '';
}

/** 已确认创建成功的行始终保留编号，部分失败后仅重试尚未成功的行。 */
export async function persistSourceSelections(
  rows: SourceTableSelection[],
  create: (row: SourceTableSelection) => Promise<TableModelApi.Id>,
  isCurrent: () => boolean,
  reconcile?: (
    row: SourceTableSelection,
  ) => Promise<TableModelApi.Id | undefined>,
) {
  for (const row of rows) {
    if (!isCurrent()) return;
    if (row.savedId !== undefined) continue;
    if (row.uncertain && reconcile) {
      try {
        const existing = await reconcile(row);
        if (!isCurrent()) return;
        if (existing !== undefined) {
          row.savedId = existing;
          row.uncertain = false;
          row.error = undefined;
          continue;
        }
      } catch {
        if (!isCurrent()) return;
        row.error = '保存结果核实失败，请重试核实';
        continue;
      }
    }
    row.error = undefined;
    try {
      const id = await create(row);
      if (!isCurrent()) return;
      row.savedId = id;
      row.uncertain = false;
    } catch {
      if (!isCurrent()) return;
      row.uncertain = true;
      if (reconcile) {
        try {
          const existing = await reconcile(row);
          if (!isCurrent()) return;
          if (existing !== undefined) {
            row.savedId = existing;
            row.uncertain = false;
            continue;
          }
        } catch {
          if (!isCurrent()) return;
        }
      }
      row.error = '保存失败或结果未确认，重试时将先核实';
    }
  }
}
