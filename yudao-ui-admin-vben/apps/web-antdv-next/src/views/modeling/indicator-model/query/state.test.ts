import { describe, expect, it } from 'vitest';

import { buildQueryRequest, caliberSummary, chartModel, rowKey } from './state';

const columns = [
  {
    key: 'dim_0',
    title: '区域',
    kind: 'DIMENSION' as const,
    fieldUid: 'region',
  },
  { key: 'time_bucket', title: '统计时间', kind: 'TIME' as const },
  {
    key: 'ind_9007199254740993',
    title: 'GMV',
    kind: 'INDICATOR' as const,
    indicatorId: '9007199254740993',
  },
];

describe('指标查询页状态', () => {
  it('组装请求：去重字符串 ID、丢弃空过滤、日期字符串原样、行数默认与上限', () => {
    const request = buildQueryRequest({
      indicatorIds: ['9007199254740993', '9007199254740993', '5'],
      dimensions: ['region'],
      filters: [
        { fieldUid: 'region', value: 'east' },
        { fieldUid: 'level', value: '' },
        { value: 'x' },
      ],
      range: ['2026-01-01', '2026-03-31'],
      timeGrain: 'MONTH',
    });
    expect(request).toEqual({
      indicatorIds: ['9007199254740993', '5'],
      dimensions: ['region'],
      filters: [{ fieldUid: 'region', value: 'east' }],
      timeRange: { start: '2026-01-01', end: '2026-03-31' },
      timeGrain: 'MONTH',
      limit: 200,
    });
    const minimal = buildQueryRequest({
      indicatorIds: ['1'],
      dimensions: [],
      filters: [],
      limit: 5000,
    });
    expect(minimal).toEqual({
      indicatorIds: ['1'],
      dimensions: undefined,
      filters: undefined,
      timeRange: undefined,
      timeGrain: undefined,
      limit: 1000,
    });
  });

  it('折线图保持十进制字符串，按时间桶对齐，缺失值为 null', () => {
    const model = chartModel({
      columns,
      rows: [
        {
          dim_0: 'east',
          time_bucket: '2026-01-01',
          ind_9007199254740993: '9007199254740993.123456789',
        },
        {
          dim_0: 'west',
          time_bucket: '2026-02-01',
          ind_9007199254740993: null,
        },
        {
          dim_0: 'east',
          time_bucket: '2026-02-01',
          ind_9007199254740993: '0.1',
        },
      ],
      truncated: false,
      calibers: [],
    })!;
    expect(model.categories).toEqual(['2026-01-01', '2026-02-01']);
    expect(model.series).toEqual([
      { name: 'GMV · east', data: ['9007199254740993.123456789', '0.1'] },
      { name: 'GMV · west', data: [null, null] },
    ]);
    expect(
      chartModel({
        columns: [columns[0]!, columns[2]!],
        rows: [],
        truncated: false,
        calibers: [],
      }),
    ).toBeUndefined();
  });

  it('系列数超过上限时省略并提示', () => {
    const rows = Array.from({ length: 25 }, (_, index) => ({
      dim_0: `r${index}`,
      time_bucket: '2026-01-01',
      ind_9007199254740993: String(index),
    }));
    const model = chartModel({
      columns,
      rows,
      truncated: false,
      calibers: [],
    })!;
    expect(model.series).toHaveLength(20);
    expect(model.omitted).toBe(true);
  });

  it('行键只由维度与时间组成且稳定', () => {
    expect(
      rowKey(
        { dim_0: 'east', time_bucket: '2026-01-01', ind_9007199254740993: '1' },
        columns,
      ),
    ).toBe('["east","2026-01-01"]');
    expect(rowKey({ dim_0: null, time_bucket: '2026-01-01' }, columns)).toBe(
      '[null,"2026-01-01"]',
    );
  });

  it('口径摘要包含聚合、周期、业务限定与来源表', () => {
    const lines = caliberSummary({
      indicatorId: '3',
      code: 'paid_gmv',
      name: '支付GMV',
      indicatorType: 'DERIVED',
      leaves: [
        {
          indicatorId: '3',
          code: 'paid_gmv',
          name: '支付GMV',
          indicatorType: 'DERIVED',
          aggregation: 'SUM',
          valueField: 'amount',
          timePeriod: 'MONTH',
          conditions: [
            {
              id: '70',
              code: 'paid',
              name: '已支付',
              expression: "pay_status = 'PAID'",
            },
          ],
          source: {
            modelId: '5',
            materializedId: '90',
            datasourceId: '50',
            datasourceName: '订单库',
            schemaName: 'dw',
            tableName: 'fact_order',
          },
        },
      ],
    });
    expect(lines[0]).toBe(
      "支付GMV (paid_gmv)：SUM(amount)，周期 MONTH，限定 已支付: pay_status = 'PAID'，来源 订单库 · dw.fact_order",
    );
  });
});
