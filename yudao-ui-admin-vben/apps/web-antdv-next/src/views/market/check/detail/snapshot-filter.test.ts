import { describe, expect, it } from 'vitest';

import { filterSnapshotRows, snapshotCellText } from './snapshot-filter';

describe('审批预览快照筛选', () => {
  const rows = [
    { ID: '9007199254740992', value: '0.12345678901234567890', name: '清源河' },
    { ID: '9007199254740993', value: '0.12345678901234567891', name: '碧川河' },
    { ID: '9007199254740994', value: null, name: '清源湖' },
  ];

  it('按字符串比较长编号且精确小数不经过数字转换', () => {
    expect(
      filterSnapshotRows(
        rows,
        [
          {
            enabled: true,
            column: 'ID',
            operator: 'eq',
            value: '9007199254740993',
          },
        ],
        'AND',
      ),
    ).toEqual([rows[1]]);
    expect(
      filterSnapshotRows(
        rows,
        [
          {
            enabled: true,
            column: 'value',
            operator: 'gt',
            value: '0.12345678901234567890',
          },
        ],
        'AND',
      ),
    ).toEqual([rows[1]]);
    expect(snapshotCellText(rows[1]?.value)).toBe('0.12345678901234567891');
  });

  it('禁用条件不参与AND/OR且空值与空字符串分开处理', () => {
    const filters = [
      {
        enabled: true,
        column: 'name',
        operator: 'like' as const,
        value: '清源',
      },
      { enabled: true, column: 'value', operator: 'isNull' as const },
      {
        enabled: false,
        column: 'ID',
        operator: 'eq' as const,
        value: '不存在',
      },
    ];
    expect(filterSnapshotRows(rows, filters, 'AND')).toEqual([rows[2]]);
    expect(filterSnapshotRows(rows, filters, 'OR')).toEqual([rows[0], rows[2]]);
    expect(
      filterSnapshotRows(
        [{ value: '' }, { value: null }],
        [{ enabled: true, column: 'value', operator: 'isNull' }],
        'AND',
      ),
    ).toEqual([{ value: null }]);
  });

  it('负数及前导零按数值顺序比较，原始结果保持原样', () => {
    const values = [
      { value: '-0002.10' },
      { value: '-2.09' },
      { value: '0002.0001' },
    ];
    expect(
      filterSnapshotRows(
        values,
        [{ enabled: true, column: 'value', operator: 'lt', value: '-2.095' }],
        'AND',
      ),
    ).toEqual([values[0]]);
    expect(
      filterSnapshotRows(
        values,
        [{ enabled: true, column: 'value', operator: 'gt', value: '2' }],
        'AND',
      ),
    ).toEqual([values[2]]);
  });
});
