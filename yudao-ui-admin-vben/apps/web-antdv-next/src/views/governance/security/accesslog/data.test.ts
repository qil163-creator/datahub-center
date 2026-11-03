import { describe, expect, it, vi } from 'vitest';

import { ACCESS_TYPE_OPTIONS, optionLabel, useGridFormSchema } from './data';

vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));

describe('访问审计筛选', () => {
  it('显示并支持筛选所有已知访问来源', () => {
    expect(ACCESS_TYPE_OPTIONS.map((item) => item.value)).toEqual([
      'API',
      'STATIC_TASK',
      'QUERY',
      'DEV',
      'EXPLORE',
      'ACCESS',
    ]);
    expect(optionLabel(ACCESS_TYPE_OPTIONS, 'QUERY')).toBe('数据查询');
    expect(optionLabel(ACCESS_TYPE_OPTIONS, 'FUTURE_TYPE')).toBe('FUTURE_TYPE');
    expect(useGridFormSchema().some((field) => field.fieldName === 'tableName')).toBe(true);
  });
});
