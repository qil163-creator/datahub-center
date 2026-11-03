import { describe, expect, it } from 'vitest';

import {
  fieldCategory,
  fieldConstraints,
  filteredDetailFields,
  summaryFieldGroups,
} from './detail-state';

describe('逻辑表详情字段契约', () => {
  it('主键同时回显非空与唯一约束，普通字段不伪造约束', () => {
    expect(fieldCategory({ pkFlag: true, fieldCategory: 'ATTRIBUTE' })).toBe(
      '主键',
    );
    expect(fieldConstraints({ pkFlag: true, nullableFlag: false })).toEqual([
      '主键',
      '非空',
      '唯一性',
    ]);
    expect(fieldConstraints({ nullableFlag: true })).toEqual([]);
    expect(fieldConstraints({ nullableFlag: false, uniqueFlag: true })).toEqual(
      ['非空', '唯一性'],
    );
    expect(fieldCategory({ pkFlag: true, fieldCategory: 'TIME' })).toBe(
      '时间字段',
    );
    expect(fieldCategory({ pkFlag: true, fieldCategory: 'GRAIN' })).toBe(
      '粒度字段',
    );
  });

  it('中英文搜索不受大小写影响并保留原始稳定字段', () => {
    const fields = [
      {
        fieldUid: '9007199254740993',
        fieldName: 'Reservoir_Id',
        cnName: '水库ID',
      },
      {
        fieldUid: '9007199254740995',
        fieldName: 'River_Name',
        cnName: '河流名称',
      },
    ];
    expect(filteredDetailFields(fields, ' reservoir ')).toEqual([fields[0]]);
    expect(filteredDetailFields(fields, '河流')).toEqual([fields[1]]);
    expect(filteredDetailFields(fields, '')).toEqual(fields);
    expect(fields[0]?.fieldUid).toBe('9007199254740993');
  });

  it('汇总分组按持久角色归类，不依赖英文名猜测', () => {
    const fields = [
      { fieldName: 'ds', fieldCategory: 'TIME' },
      { fieldName: 'region_id', fieldCategory: 'GRAIN' },
      { fieldName: 'amount', fieldCategory: 'INDICATOR' },
      { fieldName: 'ds_alias', fieldCategory: 'ATTRIBUTE' },
    ];
    expect(
      summaryFieldGroups(fields).map((group) =>
        group.fields.map((field) => field.fieldName),
      ),
    ).toEqual([['ds'], ['region_id'], ['amount']]);
  });
});
