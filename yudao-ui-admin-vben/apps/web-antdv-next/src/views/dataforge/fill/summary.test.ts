import { describe, expect, it } from 'vitest';

import { countUnreported, periodCell } from './summary';

describe('期次汇总矩阵', () => {
  it('按任务编号字符串取状态，未报单元格高亮，逾期标注', () => {
    const statuses = { '1001': 9, '1002': 1, '1003': 10 };
    const overdue = { '1001': 0, '1002': 1, '1003': 0 };
    expect(periodCell(statuses, overdue, '1001')).toEqual({ color: 'success', text: '已合并' });
    expect(periodCell(statuses, overdue, 1002)).toEqual({ color: 'error', text: '填报中·逾期' });
    expect(periodCell(statuses, overdue, '1003')).toEqual({ color: 'warning', text: '已取消' });
    expect(periodCell(statuses, overdue, '9999')).toBeUndefined();
  });

  it('统计未报期数（审批中、待合并不算未报）', () => {
    expect(countUnreported({ a: 0, b: 4, c: 5, d: 6, e: 10 })).toBe(3);
  });
});
