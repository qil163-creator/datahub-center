import { describe, expect, it, vi } from 'vitest';

import { getDictOptions } from './use-dict';

vi.mock('@vben/stores', () => ({
  useDictStore: () => ({
    getDictOptions: (type: string) =>
      (
        {
          unit: [{ label: '小时', value: '小时', remark: '时间单位' }],
          status: [{ label: '启用', value: '0' }],
        } as Record<string, unknown[]>
      )[type] ?? [],
  }),
}));

describe('字典选项分组备注', () => {
  it('字符串选项保留单位原值和备注', () => {
    expect(getDictOptions('unit', 'string')).toEqual([
      { label: '小时', value: '小时', remark: '时间单位' },
    ]);
  });

  it('缺少备注的既有字典仍按原类型转换', () => {
    expect(getDictOptions('status', 'number')).toEqual([
      { label: '启用', value: 0 },
    ]);
    expect(getDictOptions('missing')).toEqual([]);
  });
});
