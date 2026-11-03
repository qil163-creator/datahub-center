import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { describe, expect, it } from 'vitest';

import { indicatorSaveData } from './data';

const largeId = '9223372036854775806';
const atom: IndicatorModelApi.Model = {
  id: largeId,
  revision: '9007199254740993',
  indicatorType: 'ATOMIC',
  name: '支付金额',
  code: 'payment_amount',
  dataDomainId: largeId,
  status: 'DRAFT',
  sourceModelId: '9223372036854775805',
  valueFieldUid: 'stable-amount',
  timeFieldUid: 'stable-date',
  aggregation: 'SUM',
  resultDataType: 'DECIMAL',
  unit: '元',
};

describe('指标写入契约', () => {
  it('编辑保留大 ID、修订号与稳定字段，状态通过保存契约提交', () => {
    const result = indicatorSaveData(
      {
        ...atom,
        code: ' payment_amount ',
        name: ' 支付金额 ',
        expectedRevision: 1,
      },
      atom,
    );
    expect(result).toMatchObject({
      id: largeId,
      expectedRevision: '9007199254740993',
      dataDomainId: largeId,
      sourceModelId: atom.sourceModelId,
      valueFieldUid: 'stable-amount',
      code: 'payment_amount',
      name: '支付金额',
    });
    expect(result.status).toBe('DRAFT');
    expect(result).not.toHaveProperty('revision');
  });

  it('新建原子指标排除派生残留、客户端编号及修订号', () => {
    const result = indicatorSaveData({
      ...atom,
      baseIndicatorId: '5',
      businessCondition: '旧限定',
      timePeriod: 'DAY',
      timeFieldUid: '',
    });
    expect(result.id).toBeUndefined();
    expect(result.expectedRevision).toBeUndefined();
    expect(result.timeFieldUid).toBeUndefined();
    expect(result).not.toHaveProperty('baseIndicatorId');
    expect(result).not.toHaveProperty('businessCondition');
    expect(result.status).toBe('DRAFT');
  });

  it('派生仅提交原子引用、周期和限定，结果类型与单位由服务端继承', () => {
    const result = indicatorSaveData({
      ...atom,
      indicatorType: 'DERIVED',
      baseIndicatorId: largeId,
      timePeriod: 'MONTH',
      businessCondition: '仅已支付订单',
    });
    expect(result).toMatchObject({
      indicatorType: 'DERIVED',
      baseIndicatorId: largeId,
      timePeriod: 'MONTH',
      businessCondition: '仅已支付订单',
    });
    for (const key of [
      'sourceModelId',
      'valueFieldUid',
      'timeFieldUid',
      'aggregation',
      'unit',
      'resultDataType',
    ])
      expect(result).not.toHaveProperty(key);
  });

  it('编辑不能由客户端表单切换指标类型', () => {
    expect(
      indicatorSaveData({ ...atom, indicatorType: 'DERIVED' }, atom)
        .indicatorType,
    ).toBe('ATOMIC');
  });
});
