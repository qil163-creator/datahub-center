/** 提交快照行（与预览接口一致） */
export interface SnapshotRow {
  data: Record<string, unknown>;
  rowId: string;
}

export interface SnapshotDiff {
  /** 本次新增的行 */
  addedRowIds: Set<string>;
  /** 行 -> 取值发生变化的字段 */
  changedCells: Map<string, Set<string>>;
  /** 上次有、本次已删除的行 */
  removedRows: SnapshotRow[];
}

function same(left: unknown, right: unknown) {
  const normalize = (value: unknown) =>
    value === null || value === undefined ? '' : String(value).trim();
  return normalize(left) === normalize(right);
}

/**
 * 对比两次提交快照：按稳定 rowId 匹配行，空值与空串视为相同。
 * 只比较 fieldCodes 中列出的字段，避免隐藏列或历史列产生噪声。
 */
export function diffSnapshots(
  previous: readonly SnapshotRow[],
  current: readonly SnapshotRow[],
  fieldCodes: readonly string[],
): SnapshotDiff {
  const before = new Map(previous.map((row) => [row.rowId, row]));
  const seen = new Set<string>();
  const diff: SnapshotDiff = {
    addedRowIds: new Set(),
    changedCells: new Map(),
    removedRows: [],
  };
  for (const row of current) {
    seen.add(row.rowId);
    const old = before.get(row.rowId);
    if (!old) {
      diff.addedRowIds.add(row.rowId);
      continue;
    }
    const changed = new Set<string>();
    for (const code of fieldCodes) {
      if (!same(old.data?.[code], row.data?.[code])) changed.add(code);
    }
    if (changed.size > 0) diff.changedCells.set(row.rowId, changed);
  }
  diff.removedRows = previous.filter((row) => !seen.has(row.rowId));
  return diff;
}

/** 变更单元格总数 */
export function changedCellCount(diff: SnapshotDiff) {
  let count = 0;
  for (const cells of diff.changedCells.values()) count += cells.size;
  return count;
}
