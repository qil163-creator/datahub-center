import type { TableModelApi } from '#/api/modeling/tablemodel';

import { describe, expect, it } from 'vitest';

import { assertCurrentDesign, stableDesignValue } from './current-design-state';

const model = {
  id: '9007199254740993',
  revision: '9007199254740997',
} as TableModelApi.Model;
const design: TableModelApi.Design = {
  modelId: model.id,
  revision: model.revision,
  status: 'DRAFT',
  fields: [],
};

describe('当前设计与模型头的一致性', () => {
  it('接受同一模型和修订，编号保持无损字符串', () => {
    expect(() => assertCurrentDesign(model, design)).not.toThrow();
    expect(() =>
      assertCurrentDesign(
        { ...model, id: 12, revision: 3 },
        { ...design, modelId: '12', revision: '3' },
      ),
    ).not.toThrow();
  });

  it.each([{ modelId: '9007199254740994' }, { revision: '9007199254740998' }])(
    '拒绝跨模型或跨修订拼接的设计：%j',
    (change) => {
      expect(() =>
        assertCurrentDesign(model, { ...design, ...change }),
      ).toThrow('模型设计已变化，请刷新后重新编辑');
    },
  );
});

describe('未保存设计比较', () => {
  it('忽略嵌套对象的属性顺序，保留精确小数和稳定字段标识', () => {
    const fields = [
      {
        fieldUid: '9007199254740993',
        defaultValue: '9007199254740993.01',
        fieldName: 'amount',
      },
    ];
    const left = {
      fields,
      typeConfig: {
        detail: { grainFields: ['amount'], organizationMode: 'TRANSACTION' },
      },
    };
    const right = {
      typeConfig: {
        detail: { organizationMode: 'TRANSACTION', grainFields: ['amount'] },
      },
      fields,
    };
    expect(stableDesignValue(left)).toBe(stableDesignValue(right));
    expect(stableDesignValue(left)).toContain('9007199254740993.01');
    expect(stableDesignValue(left)).toContain('9007199254740993');
  });

  it('字段重排、清空与缺省值仍视为不同设计', () => {
    expect(stableDesignValue(['first', 'second'])).not.toBe(
      stableDesignValue(['second', 'first']),
    );
    expect(stableDesignValue({ value: undefined })).not.toBe(
      stableDesignValue({ value: null }),
    );
    expect(stableDesignValue({ value: '' })).not.toBe(
      stableDesignValue({ value: undefined }),
    );
  });
});
