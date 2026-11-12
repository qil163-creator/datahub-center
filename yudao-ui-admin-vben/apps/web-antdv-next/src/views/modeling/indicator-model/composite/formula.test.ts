import { describe, expect, it } from 'vitest';

import {
  checkFormula,
  compositeSaveData,
  dependencyTree,
  insertReference,
} from './formula';

describe('复合指标公式编辑', () => {
  it('接受数字、四则运算、括号与指标引用，并按首次出现去重引用', () => {
    const result = checkFormula(
      '({Gmv} - {refund}) / {order_cnt} * 100 + -{gmv}',
    );
    expect(result.ok).toBe(true);
    expect(result.references).toEqual(['Gmv', 'refund', 'order_cnt']);
  });

  it.each([
    ['', '公式不能为空'],
    ['100', '至少需要引用一个指标'],
    ['{gmv} +', '公式不完整'],
    ['({gmv}', '左括号未闭合'],
    ['{gmv})', '多余内容'],
    ['{gmv} % 2', '不允许出现在公式中'],
    ['sum({gmv})', '不允许出现在公式中'],
    ['{ gmv }', '必须是字母开头的英文编码'],
    ['{gmv', '缺少 }'],
    ['1. + {gmv}', '数字格式不正确'],
    ['{gmv}; DROP TABLE x', '不允许出现在公式中'],
  ])('拒绝非法公式 %s', (formula, message) => {
    const result = checkFormula(formula);
    expect(result.ok).toBe(false);
    expect(result.message).toContain(message);
  });

  it('在光标处插入引用并补空格', () => {
    expect(insertReference('{a} / ', 'b')).toEqual({
      value: '{a} / {b}',
      cursor: 9,
    });
    expect(insertReference('{a}+', 'b', 3, 3)).toEqual({
      value: '{a} {b} +',
      cursor: 7,
    });
    expect(insertReference('(', 'gmv')).toEqual({ value: '({gmv}', cursor: 6 });
    expect(insertReference('{a} * 2', 'b', 4, 5).value).toBe('{a} {b} 2');
  });

  it('保存数据只包含复合指标可写字段，ID 与修订号原样传递', () => {
    const data = compositeSaveData(
      {
        code: ' avg_price ',
        name: ' 客单价 ',
        dataDomainId: '9007199254740993',
        formula: ' {gmv} / {cnt} ',
        unit: ' ',
      },
      {
        id: '9007199254740995',
        revision: '9007199254740997',
        status: 'DRAFT',
      } as any,
    );
    expect(data).toEqual({
      id: '9007199254740995',
      expectedRevision: '9007199254740997',
      indicatorType: 'COMPOSITE',
      code: 'avg_price',
      name: '客单价',
      dataDomainId: '9007199254740993',
      subjectDomainId: undefined,
      ownerUserId: undefined,
      status: undefined,
      unit: undefined,
      definition: undefined,
      description: undefined,
      formula: '{gmv} / {cnt}',
    });
    expect(data).not.toHaveProperty('sourceModelId');
    expect(
      compositeSaveData({
        code: 'a',
        name: 'b',
        dataDomainId: '1',
        formula: '{x}',
      }).status,
    ).toBe('DRAFT');
  });

  it('依赖树节点 key 带路径，共享节点在不同分支也唯一', () => {
    const shared = {
      id: '1',
      code: 'gmv',
      name: 'GMV',
      indicatorType: 'ATOMIC' as const,
      status: 'ACTIVE' as const,
      children: [],
    };
    const tree = dependencyTree([
      {
        id: '5',
        code: 'avg',
        name: '客单价',
        indicatorType: 'COMPOSITE',
        status: 'ACTIVE',
        formula: '{gmv} / {cnt}',
        children: [shared],
      },
      shared,
    ]);
    expect(tree[0]!.title).toBe('[复合] 客单价 (avg) = {gmv} / {cnt}');
    expect(tree[0]!.children[0]!.key).not.toBe(tree[1]!.key);
    expect(tree[1]!.title).toBe('[原子] GMV (gmv)');
  });
});
