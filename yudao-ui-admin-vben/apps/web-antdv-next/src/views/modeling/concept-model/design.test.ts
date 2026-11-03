import type { ConceptModelApi } from '#/api/modeling/concept-model';

import { describe, expect, it } from 'vitest';

import { removeDesignEntity, validateDesign } from './design';

function fixture(): ConceptModelApi.Design {
  return {
    entities: [
      { key: 'customer', name: '客户', code: 'customer', attributes: [] },
      { key: 'order', name: '订单', code: 'order', attributes: [] },
      { key: 'product', name: '产品', code: 'product', attributes: [] },
    ],
    relations: [
      {
        key: 'r1',
        name: '下单',
        sourceEntityKey: 'customer',
        targetEntityKey: 'order',
        cardinality: 'ONE_TO_MANY',
      },
      {
        key: 'r2',
        name: '购买',
        sourceEntityKey: 'order',
        targetEntityKey: 'product',
        cardinality: 'MANY_TO_MANY',
      },
    ],
  };
}

describe('概念模型设计边界', () => {
  it('同显示名的实体通过不同编码和稳定编号区分，保留活动类型', () => {
    const design = fixture();
    design.entities.push({ key: 'customer2', name: '客户', code: 'customer2', entityType: 'ACTIVITY', attributes: [] });
    expect(validateDesign(design)).toBeUndefined();
    expect(design.entities[3]!.entityType).toBe('ACTIVITY');
    expect(design.relations[0]!.sourceEntityKey).toBe('customer');
  });
  it('删除实体只移除相关关系，保留其他稳定标识', () => {
    const design = fixture();
    removeDesignEntity(design, 'customer');
    expect(design.entities.map((entity) => entity.key)).toEqual([
      'order',
      'product',
    ]);
    expect(design.relations.map((relation) => relation.key)).toEqual(['r2']);
    expect(validateDesign(design)).toBeUndefined();
  });
  it('拒绝大小写不同的重复编码和属性编码', () => {
    const design = fixture();
    design.entities[1]!.code = ' CUSTOMER ';
    expect(validateDesign(design)).toContain('重复');
    design.entities[1]!.code = 'order';
    design.entities[0]!.attributes = [
      { key: 'a1', name: '编号', code: 'id' },
      { key: 'a2', name: '客户号', code: 'ID' },
    ];
    expect(validateDesign(design)).toContain('属性名称或编码重复');
  });
  it('拒绝悬空、自引用和同方向重复关系', () => {
    const design = fixture();
    design.relations[0]!.targetEntityKey = 'missing';
    expect(validateDesign(design)).toContain('有效的起点和终点');
    design.relations[0]!.targetEntityKey = 'customer';
    expect(validateDesign(design)).toContain('不能相同');
    design.relations[0]!.targetEntityKey = 'order';
    design.relations.push({ ...design.relations[0]!, key: 'r3' });
    expect(validateDesign(design)).toContain('相同方向');
  });
  it('允许空草稿，超限设计不能提交', () => {
    expect(validateDesign({ entities: [], relations: [] })).toBeUndefined();
    const entity = fixture().entities[0]!;
    expect(
      validateDesign({
        entities: Array.from({ length: 51 }, () => ({ ...entity })),
        relations: [],
      }),
    ).toContain('50');
  });
});
