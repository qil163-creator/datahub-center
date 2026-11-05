import { describe, expect, it } from 'vitest';

import { changedCellCount, diffSnapshots } from './diff';

const row = (rowId: string, data: Record<string, unknown>) => ({ data, rowId });

describe('提交快照差异', () => {
  it('按稳定行标识识别新增、删除和修改的单元格', () => {
    const previous = [row('a', { name: '甲', amount: 10 }), row('b', { name: '乙', amount: 20 }), row('c', { name: '丙', amount: 30 })];
    const current = [row('a', { name: '甲', amount: 11 }), row('c', { name: '丙', amount: 30 }), row('d', { name: '丁', amount: 40 })];
    const diff = diffSnapshots(previous, current, ['name', 'amount']);
    expect([...diff.addedRowIds]).toEqual(['d']);
    expect(diff.removedRows.map((item) => item.rowId)).toEqual(['b']);
    expect([...diff.changedCells.get('a')!]).toEqual(['amount']);
    expect(diff.changedCells.has('c')).toBe(false);
    expect(changedCellCount(diff)).toBe(1);
  });

  it('空值、空串和首尾空白视为相同，数字与字符串形式视为相同', () => {
    const diff = diffSnapshots(
      [row('a', { x: null, y: ' 5 ', z: undefined })],
      [row('a', { x: '', y: 5, z: '' })],
      ['x', 'y', 'z'],
    );
    expect(diff.changedCells.size).toBe(0);
  });

  it('只比较指定字段，行顺序变化不算修改，无上次快照时全部视为新增', () => {
    const diff = diffSnapshots(
      [row('a', { hidden: 1, name: 'x' }), row('b', { name: 'y' })],
      [row('b', { name: 'y' }), row('a', { hidden: 2, name: 'x' })],
      ['name'],
    );
    expect(diff.changedCells.size).toBe(0);
    expect(diff.addedRowIds.size).toBe(0);
    const all = diffSnapshots([], [row('a', {}), row('b', {})], ['name']);
    expect([...all.addedRowIds]).toEqual(['a', 'b']);
  });
});
