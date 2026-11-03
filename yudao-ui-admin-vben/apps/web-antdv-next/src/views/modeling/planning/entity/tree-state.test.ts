import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { describe, expect, it } from 'vitest';

import { normalizeEntityTree } from './tree-state';

function entity(id: BusinessEntityApi.Id): BusinessEntityApi.Entity {
  return {
    id,
    dataDomainId: '29',
    subjectDomainId: '55',
    name: '业务实体',
    code: 'ENTITY',
    entityType: 'ACTIVITY',
    entitySubtype: 'EVENT',
    description: '',
    associatedEntityIds: [],
    predecessorEntityIds: [],
    processEventIds: [],
    status: 0,
    revision: '9007199254740999',
  };
}

describe('业务实体树表数据', () => {
  it('递归规范编号并保留流程包含事件的服务端顺序', () => {
    const result = normalizeEntityTree([
      {
        ...entity('9007199254740995'),
        entitySubtype: 'PROCESS',
        children: [
          {
            ...entity('9007199254741007'),
            inheritEntityId: '9007199254741011',
            children: [{ ...entity(71), inheritEntityId: 75 }],
          },
          entity('9007199254741005'),
        ],
      },
      entity(74),
    ]);

    expect(result.map((row) => row.id)).toEqual(['9007199254740995', '74']);
    expect(result[0]?.children?.map((row) => row.id)).toEqual([
      '9007199254741007',
      '9007199254741005',
    ]);
    expect(result[0]?.children?.[0]?.inheritEntityId).toBe('9007199254741011');
    expect(result[0]?.children?.[0]?.children?.[0]).toMatchObject({
      id: '71',
      inheritEntityId: '75',
      revision: '9007199254740999',
    });
  });

  it('空、缺失和 null 子行均保持叶子行，null 继承编号不变成字符串', () => {
    const result = normalizeEntityTree([
      entity(1),
      { ...entity(2), children: [], inheritEntityId: null },
      { ...entity(3), children: null },
    ]);

    for (const row of result) {
      expect(row).not.toHaveProperty('children');
      expect(row.inheritEntityId).toBeUndefined();
    }
    expect(normalizeEntityTree([])).toEqual([]);
  });

  it('创建独立树节点而不修改接口响应及原有编号', () => {
    const child = Object.freeze({ ...entity(71), inheritEntityId: 75 });
    const children = Object.freeze([child]);
    const source = Object.freeze([Object.freeze({ ...entity(75), children })]);
    const result = normalizeEntityTree(source);

    expect(result[0]).not.toBe(source[0]);
    expect(result[0]?.children).not.toBe(children);
    expect(result[0]?.children?.[0]).not.toBe(child);
    expect(source[0]?.id).toBe(75);
    expect(child.id).toBe(71);
    expect(child.inheritEntityId).toBe(75);
  });
});
