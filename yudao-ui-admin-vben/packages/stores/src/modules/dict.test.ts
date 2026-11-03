import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';

import { useDictStore } from './dict';

describe('字典值查询', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it.each([undefined, null])('非空字典查询空值 %s 时返回未匹配', (value) => {
    const store = useDictStore();
    store.setDictCache({ status: [{ value: '0', label: '启用' }] });

    expect(store.getDictData('status', value)).toBeUndefined();
  });

  it.each([
    { value: 0, stored: '0' },
    { value: false, stored: 'false' },
    { value: '', stored: '' },
    { value: '9223372036854775807', stored: '9223372036854775807' },
  ])('保留有效字典值 $value 的匹配行为', ({ value, stored }) => {
    const store = useDictStore();
    const item = { value: stored, label: '匹配项' };
    store.setDictCache({ status: [item] });

    expect(store.getDictData('status', value)).toEqual(item);
  });

  it('字典未加载或值不存在时返回未匹配', () => {
    const store = useDictStore();
    expect(store.getDictData('status', 'missing')).toBeUndefined();
    store.setDictCache({ status: [{ value: '0', label: '启用' }] });
    expect(store.getDictData('status', 'missing')).toBeUndefined();
  });

  it('字典接口缓存保留可选分组备注及自定义标签字段', async () => {
    const store = useDictStore();
    store.setDictCacheByApi(
      async () => [
        { dictType: 'unit', text: '小时', key: '小时', remark: '时间单位' },
        { dictType: 'status', text: '启用', key: '0' },
      ],
      {},
      'text',
      'key',
    );
    await Promise.resolve();

    expect(store.getDictOptions('unit')).toEqual([
      { label: '小时', value: '小时', remark: '时间单位' },
    ]);
    expect(store.getDictOptions('status')).toEqual([
      { label: '启用', value: '0' },
    ]);
  });
});
