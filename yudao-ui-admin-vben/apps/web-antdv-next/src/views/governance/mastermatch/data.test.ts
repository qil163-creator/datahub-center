import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterMatchApi } from '#/api/governance/mastermatch';

import { describe, expect, it } from 'vitest';

import {
  algorithmOptions,
  chooseSurvivor,
  displayValue,
  goldenValues,
  initialDraft,
  keyLabel,
  memberSummary,
  mergeIssue,
  mergeRequest,
  survivorshipOptions,
  toggleParticipant,
} from './data';

const columns: GovernanceMasterDataApi.Column[] = [
  { name: 'code', pk: 1, fieldType: 2 },
  { name: 'name', pk: 0, fieldType: 2 },
  { name: 'phone', pk: 0, fieldType: 2 },
];

function group(
  overrides: Partial<GovernanceMasterMatchApi.Group> = {},
): GovernanceMasterMatchApi.Group {
  return {
    id: 7,
    runId: 1,
    modelId: 1,
    status: 'PENDING',
    memberCount: 3,
    maxScore: '0.9800',
    members: [
      { key: '{"code":"C1"}', row: { code: 'C1', name: '华为', phone: null } },
      {
        key: '{"code":"C2"}',
        row: { code: 'C2', name: '华为技术', phone: '0755' },
      },
      {
        key: '{"code":"C3"}',
        row: { code: 'C3', name: '华为公司', phone: '010' },
      },
    ],
    suggestion: {
      survivor: 1,
      values: {},
      sources: { code: 1, name: 1, phone: 2 },
    },
    ...overrides,
  };
}

describe('相似匹配选项', () => {
  it('按字段类型限制算法与取值规则', () => {
    expect(algorithmOptions(2).map((option) => option.value)).toEqual([
      'EXACT',
      'TEXT',
      'EDIT',
    ]);
    expect(algorithmOptions(6).map((option) => option.value)).toEqual([
      'EXACT',
      'NUMERIC',
    ]);
    expect(algorithmOptions(3).map((option) => option.value)).toEqual([
      'EXACT',
    ]);
    expect(survivorshipOptions(4).map((option) => option.value)).not.toContain(
      'MAX',
    );
  });

  it('主键标识与空值显示', () => {
    expect(keyLabel('{"code":"C1"}')).toBe('C1');
    expect(keyLabel('{"a":1,"b":"x"}')).toBe('a=1，b=x');
    expect(keyLabel('SHA256:abcdef0123456789')).toBe('主键摘要 abcdef01');
    expect(keyLabel('not-json')).toBe('not-json');
    expect(displayValue(null)).toBe('（空）');
    expect(displayValue(0)).toBe('0');
    expect(
      memberSummary(group().members[1]!, ['name', 'phone', 'code']),
    ).toBe('C2 · 华为技术 · 0755');
  });
});

describe('合并草稿', () => {
  it('按建议初始化，生成的黄金记录主键取保留记录、其余按来源', () => {
    const value = group();
    const draft = initialDraft(value, columns);
    expect(draft.survivor).toBe(1);
    expect(goldenValues(value, draft, columns)).toEqual({
      code: 'C2',
      name: '华为技术',
      phone: '010',
    });
    expect(mergeIssue(value, draft)).toBeUndefined();
    expect(mergeRequest(value, draft, columns, 'req-1')).toEqual({
      groupId: 7,
      requestId: 'req-1',
      survivorKey: '{"code":"C2"}',
      mergedKeys: ['{"code":"C1"}', '{"code":"C3"}'],
      values: { code: 'C2', name: '华为技术', phone: '010' },
    });
  });

  it('切换保留记录后主键跟随，取消参与的成员不再作为取值来源', () => {
    const value = group();
    const draft = initialDraft(value, columns);
    chooseSurvivor(draft, 0, columns);
    expect(goldenValues(value, draft, columns).code).toBe('C1');
    toggleParticipant(draft, 2, false);
    expect(draft.sources.phone).toBe(0);
    expect(mergeRequest(value, draft, columns, 'r').mergedKeys).toEqual([
      '{"code":"C2"}',
    ]);
    toggleParticipant(draft, 0, false);
    expect(draft.participating[0]).toBe(true);
    toggleParticipant(draft, 1, false);
    expect(mergeIssue(value, draft)).toContain('至少选择一条');
  });

  it('已不存在的成员默认不参与，非待处理的组不能合并', () => {
    const value = group({
      members: [
        ...group().members.slice(0, 2),
        { ...group().members[2]!, exists: false },
      ],
    });
    const draft = initialDraft(value, columns);
    expect(draft.participating).toEqual([true, true, false]);
    expect(draft.sources.phone).toBe(1);
    expect(mergeIssue(group({ status: 'MERGED' }), draft)).toBe(
      '该组当前不能合并',
    );
  });
});
