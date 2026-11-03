import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { describe, expect, it } from 'vitest';

import {
  atomicExpression,
  atomicSourceMatches,
  atomicTimeFields,
  atomicUnitTree,
} from './atomic-state';

describe('原子指标来源定义', () => {
  const selection = {
    dataDomainId: '9007199254740993',
    subjectDomainId: '9007199254740994',
    entityId: '9007199254740995',
    dataFreshness: '1',
  };
  const model = {
    ...selection,
    dataTimeliness: '1',
    enabled: 1,
    tableType: 'DETAIL',
    status: 'PUBLISHED',
  } as unknown as TableModelApi.Model;
  it('来源同时受启用状态、类型、板块、主题、实体及时效约束', () => {
    expect(atomicSourceMatches(model, selection)).toBe(true);
    for (const value of [
      { enabled: 0 },
      { status: 'DRAFT' },
      { status: 'DISCARDED' },
      { tableType: 'SUMMARY' },
      { dataDomainId: '9' },
      { subjectDomainId: '9' },
      { entityId: '9' },
      { dataTimeliness: '2' },
    ])
      expect(atomicSourceMatches({ ...model, ...value }, selection)).toBe(
        false,
      );
    expect(
      atomicSourceMatches(model, { ...selection, entityId: undefined }),
    ).toBe(false);
  });
  it('表达式来自聚合与稳定字段，时间选项仅日期时间类型', () => {
    const field: IndicatorModelApi.SourceField = {
      fieldUid: 'amount-uid',
      fieldName: 'payment_amount',
      dataType: 'DECIMAL',
    };
    expect(atomicExpression('SUM', field)).toBe('sum(payment_amount)');
    expect(atomicExpression('COUNT_DISTINCT', field)).toBe(
      'count(distinct payment_amount)',
    );
    expect(atomicExpression('MAX')).toBe('');
    const fields = ['VARCHAR', 'DATE', 'DATETIME', 'TIMESTAMP(6)', 'TIME'].map(
      (dataType) => ({
        fieldUid: dataType,
        fieldName: dataType.toLowerCase(),
        dataType,
      }),
    );
    expect(atomicTimeFields(fields).map((item) => item.dataType)).toEqual([
      'DATE',
      'DATETIME',
      'TIMESTAMP(6)',
      'TIME',
    ]);
  });
  it('真实字典按备注保持目录顺序、父组不可选，现有自由单位无损回显', () => {
    const units = atomicUnitTree(
      [
        { label: '其他', value: '其他', remark: '其他' },
        { label: '元(人民币)', value: '元(人民币)', remark: '货币单位' },
        { label: '万元(人民币)', value: '万元(人民币)', remark: '货币单位' },
      ],
      '毫米',
    );
    expect(units.map((group) => group.title)).toEqual([
      '其他',
      '货币单位',
      '当前单位',
    ]);
    expect(units.every((group) => group.selectable === false)).toBe(true);
    expect(units[1]?.children.map((item) => item.value)).toEqual([
      '元(人民币)',
      '万元(人民币)',
    ]);
    expect(units[2]?.children[0]?.value).toBe('毫米');
    expect(
      atomicUnitTree(
        [{ label: '毫米', value: '毫米', remark: '长度' }],
        '毫米',
      ),
    ).toHaveLength(1);
  });
});
