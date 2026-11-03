import { describe, expect, it } from 'vitest';

import { buildCodeItemTree, validateHalfOpenInterval } from './model';

describe('标准码值层级与有效期', () => {
  it('builds an ordered hierarchy without mutating the source rows', () => {
    const rows = [
      {
        codeSetId: 8,
        dictType: 'region',
        id: 3,
        label: '华东',
        parentId: 1,
        sort: 2,
        value: 'E',
      },
      {
        codeSetId: 8,
        dictType: 'region',
        id: 1,
        label: '中国',
        sort: 1,
        value: 'CN',
      },
      {
        codeSetId: 8,
        dictType: 'region',
        id: 2,
        label: '华北',
        parentId: 1,
        sort: 1,
        value: 'N',
      },
    ];

    expect(buildCodeItemTree(rows)).toEqual([
      expect.objectContaining({
        id: 1,
        levelNo: 0,
        children: [
          expect.objectContaining({ id: 2, levelNo: 1 }),
          expect.objectContaining({ id: 3, levelNo: 1 }),
        ],
      }),
    ]);
    expect(rows.every((row) => !('children' in row))).toBe(true);
  });

  it('breaks dirty parent cycles and emits every code item exactly once', () => {
    const tree = buildCodeItemTree([
      {
        codeSetId: 8,
        dictType: 'region',
        id: 1,
        label: '甲',
        parentId: 2,
        value: 'A',
      },
      {
        codeSetId: 8,
        dictType: 'region',
        id: 2,
        label: '乙',
        parentId: 1,
        value: 'B',
      },
    ]);
    const ids: (number | string)[] = [];
    const visit = (rows: typeof tree) => {
      for (const row of rows) {
        ids.push(row.id!);
        visit(row.children ?? []);
      }
    };

    expect(() => JSON.stringify(tree)).not.toThrow();
    visit(tree);
    expect(
      ids.toSorted((left, right) => String(left).localeCompare(String(right))),
    ).toEqual([1, 2]);
    expect(new Set(ids).size).toBe(2);
  });

  it('preserves unsafe IDs, mixed JSON ID types and nested children without loss', () => {
    const parent = '9007199254740993';
    const child = '9007199254740995';
    const tree = buildCodeItemTree([
      {
        id: parent,
        codeSetId: 8,
        dictType: 'region',
        label: '同名',
        value: 'A',
        children: [
          {
            id: child,
            codeSetId: '8',
            dictType: 'region',
            label: '同名',
            value: 'B',
            parentId: parent,
          },
        ],
      },
    ]);
    expect(tree).toHaveLength(1);
    expect(tree[0]?.id).toBe(parent);
    expect(tree[0]?.children?.[0]?.id).toBe(child);
    expect(tree[0]?.children?.[0]?.parentId).toBe(parent);
  });

  it('rejects malformed left-closed/right-open intervals', () => {
    expect(validateHalfOpenInterval(undefined, undefined)).toBeUndefined();
    expect(
      validateHalfOpenInterval('2026-09-01 00:00:00', '2026-09-02 00:00:00'),
    ).toBeUndefined();
    expect(
      validateHalfOpenInterval('2026-09-02 00:00:00', '2026-09-02 00:00:00'),
    ).toBe('失效时间必须晚于生效时间（有效期左闭右开）');
    expect(
      validateHalfOpenInterval('2026-09-03 00:00:00', '2026-09-02 00:00:00'),
    ).toBe('失效时间必须晚于生效时间（有效期左闭右开）');
  });
});
