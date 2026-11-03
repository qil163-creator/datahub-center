export const SNAPSHOT_OPERATORS = [
  { label: '=', value: 'eq' },
  { label: '≠', value: 'ne' },
  { label: '包含', value: 'like' },
  { label: '>', value: 'gt' },
  { label: '≥', value: 'ge' },
  { label: '<', value: 'lt' },
  { label: '≤', value: 'le' },
  { label: '为空', value: 'isNull' },
  { label: '不为空', value: 'notNull' },
] as const;

export interface SnapshotFilter {
  column?: string;
  enabled: boolean;
  operator: (typeof SNAPSHOT_OPERATORS)[number]['value'];
  value?: string;
}

export function snapshotCellText(value: unknown): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

export function compareSnapshotValues(left: string, right: string) {
  const pattern = /^([+-]?)(\d+)(?:\.(\d+))?$/;
  const a = pattern.exec(left);
  const b = pattern.exec(right);
  if (!a || !b) return left.localeCompare(right);
  const integerA = a[2]!.replace(/^0+/, '') || '0';
  const integerB = b[2]!.replace(/^0+/, '') || '0';
  const fractionA = (a[3] || '').replace(/0+$/, '');
  const fractionB = (b[3] || '').replace(/0+$/, '');
  const negativeA = a[1] === '-' && (integerA !== '0' || !!fractionA);
  const negativeB = b[1] === '-' && (integerB !== '0' || !!fractionB);
  if (negativeA !== negativeB) return negativeA ? -1 : 1;
  const magnitude =
    integerA.length - integerB.length ||
    integerA.localeCompare(integerB) ||
    fractionA
      .padEnd(Math.max(fractionA.length, fractionB.length), '0')
      .localeCompare(
        fractionB.padEnd(Math.max(fractionA.length, fractionB.length), '0'),
      );
  return negativeA ? -magnitude : magnitude;
}

export function filterSnapshotRows(
  rows: Record<string, unknown>[],
  filters: SnapshotFilter[],
  relation: 'AND' | 'OR',
) {
  const active = filters.filter(
    (filter) =>
      filter.enabled &&
      filter.column &&
      (['isNull', 'notNull'].includes(filter.operator) ||
        (filter.value !== undefined && filter.value !== '')),
  );
  if (active.length === 0) return rows;
  return rows.filter((row) => {
    const matches = active.map((filter) => {
      const value = row[filter.column!];
      if (filter.operator === 'isNull')
        return value === undefined || value === null;
      if (filter.operator === 'notNull')
        return value !== undefined && value !== null;
      if (value === undefined || value === null) return false;
      const text = snapshotCellText(value);
      const target = filter.value || '';
      if (filter.operator === 'eq') return text === target;
      if (filter.operator === 'ne') return text !== target;
      if (filter.operator === 'like')
        return text.toLowerCase().includes(target.toLowerCase());
      const comparison = compareSnapshotValues(text, target);
      if (filter.operator === 'gt') return comparison > 0;
      if (filter.operator === 'ge') return comparison >= 0;
      if (filter.operator === 'lt') return comparison < 0;
      return comparison <= 0;
    });
    return relation === 'OR' ? matches.some(Boolean) : matches.every(Boolean);
  });
}
