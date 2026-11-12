import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getActiveIndicatorOptions,
  getIndicatorQueryMeta,
  queryIndicatorData,
  validateIndicatorFormula,
} from './index';

const client = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: client }));
beforeEach(() => vi.clearAllMocks());

describe('指标查询与复合指标接口契约', () => {
  it('查询请求保持大整数 ID 为字符串，路径不重复 admin-api', async () => {
    const request = {
      indicatorIds: ['9007199254740993'],
      dimensions: ['region-uid'],
      timeRange: { start: '2026-01-01', end: '2026-03-31' },
      timeGrain: 'MONTH' as const,
      limit: 100,
    };
    await queryIndicatorData(request);
    expect(client.post).toHaveBeenCalledWith(
      '/modeling/indicator-model/query',
      request,
    );
    await getIndicatorQueryMeta(['9007199254740993']);
    expect(client.post).toHaveBeenCalledWith(
      '/modeling/indicator-model/query-meta',
      {
        indicatorIds: ['9007199254740993'],
      },
    );
  });

  it('公式校验与启用指标选项走定义接口', async () => {
    const data = {
      dataDomainId: '7',
      code: 'avg_price',
      formula: '{gmv} / {order_cnt}',
    };
    await validateIndicatorFormula(data);
    expect(client.post).toHaveBeenCalledWith(
      '/modeling/indicator-model/validate-formula',
      data,
    );
    await getActiveIndicatorOptions('7');
    expect(client.get).toHaveBeenCalledWith(
      '/modeling/indicator-model/active-options',
      {
        params: { dataDomainId: '7' },
      },
    );
  });

  it('脱敏列契约与共享表头组件一致：name 为结果行列 key', async () => {
    const { indexMaskedColumns, maskedColumnHint } =
      await import('#/components/masked-column');
    const response = {
      columns: [],
      rows: [{ dim_0: '138****1234' }],
      truncated: false,
      calibers: [],
      maskedColumns: [
        { name: 'dim_0', reason: 'RULE', categoryNames: ['手机号'] },
      ],
    };
    client.post.mockResolvedValueOnce(response);
    const result = await queryIndicatorData({ indicatorIds: ['1'] });
    const info = indexMaskedColumns(result.maskedColumns).get('dim_0');
    expect(info && maskedColumnHint(info)).toBe(
      '已脱敏（手机号）：命中分类脱敏规则',
    );
  });
});
