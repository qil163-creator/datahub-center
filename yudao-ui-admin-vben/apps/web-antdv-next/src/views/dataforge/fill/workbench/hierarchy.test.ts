import { describe, expect, it } from 'vitest';

import { childActions, defaultChildDeadline, subtreeDeptIds } from './hierarchy';

describe('多级下发辅助函数', () => {
  it('按候选部门计算子树，编号按字符串比较', () => {
    const depts = [
      { deptId: '9007199254740993', parentId: 100 },
      { deptId: 201, parentId: '9007199254740993' },
      { deptId: 202, parentId: 201 },
      { deptId: 300, parentId: 100 },
    ];
    expect([...subtreeDeptIds(depts, '9007199254740993')].toSorted()).toEqual([
      '201',
      '202',
      '9007199254740993',
    ]);
    expect([...subtreeDeptIds(depts, 300)]).toEqual(['300']);
  });

  it('默认截止时间按上级截止提前，过早时取上级截止时间', () => {
    const parent = Date.UTC(2026, 10, 20, 10);
    const now = Date.UTC(2026, 10, 13, 10);
    expect(defaultChildDeadline(parent, 24, now)).toBe(parent - 24 * 3_600_000);
    expect(defaultChildDeadline(parent, 24 * 30, now)).toBe(parent);
    expect(defaultChildDeadline(undefined, 24, now)).toBeUndefined();
  });

  it('上级已上报后不能再处理下级', () => {
    expect(childActions({ status: 4, awaitingReview: true, currentSubmissionId: '7' }, true)).toMatchObject({
      review: true,
      view: true,
      cancel: false,
    });
    expect(childActions({ status: 11, currentSubmissionId: 7 }, true).returnBack).toBe(true);
    expect(childActions({ status: 1 }, true)).toMatchObject({ cancel: true, deadline: true, view: false });
    expect(Object.values(childActions({ status: 4, awaitingReview: true }, false)).some(Boolean)).toBe(false);
  });
});
