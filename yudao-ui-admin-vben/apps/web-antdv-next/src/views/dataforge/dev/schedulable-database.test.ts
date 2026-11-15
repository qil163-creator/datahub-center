import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getDevSchedulableProducts } from '#/api/dataforge/dev';

import {
  isSchedulableDatabase,
  loadSchedulableProducts,
  schedulableDatabaseOption,
} from './schedulable-database';

vi.mock('#/api/dataforge/dev', () => ({
  getDevSchedulableProducts: vi.fn(),
}));

describe('可调度数据源提示', () => {
  beforeEach(() => {
    vi.mocked(getDevSchedulableProducts).mockReset();
  });

  it('按后端产品清单判断，产品缺失的连接视为不可调度', async () => {
    vi.mocked(getDevSchedulableProducts).mockResolvedValue([
      'MYSQL',
      'POSTGRESQL',
    ]);
    const products = await loadSchedulableProducts();
    expect(isSchedulableDatabase('MYSQL', products)).toBe(true);
    expect(isSchedulableDatabase('CLICKHOUSE', products)).toBe(false);
    expect(isSchedulableDatabase(undefined, products)).toBe(false);
  });

  it('清单读取失败时不做界面限制，交由后端校验', async () => {
    vi.mocked(getDevSchedulableProducts).mockRejectedValue(new Error('网络'));
    const products = await loadSchedulableProducts();
    expect(products).toBeUndefined();
    expect(isSchedulableDatabase('CLICKHOUSE', products)).toBeUndefined();
    expect(
      schedulableDatabaseOption({ label: '分析库', value: '1' }),
    ).toEqual({ label: '分析库', value: '1', disabled: false });
  });

  it('不可调度的选项保留大编号字符串并标注', () => {
    expect(
      schedulableDatabaseOption({
        label: '分析库',
        value: '9007199254740993',
        schedulable: false,
      }),
    ).toEqual({
      label: '分析库（暂不支持调度）',
      value: '9007199254740993',
      disabled: true,
    });
  });
});
