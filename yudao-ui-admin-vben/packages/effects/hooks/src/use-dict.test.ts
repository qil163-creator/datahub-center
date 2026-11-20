import { describe, expect, it, vi } from 'vitest';

import { getDictOptions, groupDictData } from './use-dict';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: '' }),
  useDictStore: () => ({
    dictMap: {
      unit: [{ label: '小时', value: '小时', remark: '时间单位' }],
      status: [{ label: '启用', value: '0' }],
    },
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

  it('全量字典分组保留备注', () => {
    expect(
      groupDictData([
        { dictType: 'unit', label: '小时', value: '小时', remark: '时间单位' },
        { dictType: 'unit', label: '个', value: '个' },
      ]),
    ).toEqual({
      unit: [
        { label: '小时', value: '小时', remark: '时间单位' },
        { label: '个', value: '个' },
      ],
    });
  });
});
