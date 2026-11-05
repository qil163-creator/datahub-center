import { describe, expect, it } from 'vitest';

import { describeColumnRules, rangePlaceholder, supportsRange } from './data';

describe('填报表字段规则展示', () => {
  it('只有数值和日期时间字段支持取值范围', () => {
    for (const type of [1, 3, 5, 6, 9, 10]) expect(supportsRange(type)).toBe(true);
    expect(supportsRange(2)).toBe(false);
    expect(supportsRange(undefined)).toBe(false);
    expect(rangePlaceholder(3)).toContain('2026-01-01');
    expect(rangePlaceholder(10)).toContain('08:00:00');
  });

  it('只描述已配置的规则，范围缺一端用无穷表示', () => {
    expect(describeColumnRules({ fieldType: 5 })).toEqual([]);
    expect(describeColumnRules({ fieldType: 5, minValue: '0', maxValue: '100' })).toEqual(['范围 0 ~ 100']);
    expect(describeColumnRules({ fieldType: 1, maxValue: '9.5' })).toEqual(['范围 -∞ ~ 9.5']);
    expect(describeColumnRules({ fieldType: 3, minValue: '2026-01-01' })).toEqual(['范围 2026-01-01 ~ +∞']);
    // 字符串字段残留的范围值不展示；正则和唯一展示
    expect(describeColumnRules({ fieldType: 2, minValue: '1', regexPattern: '[0-9]+', uniqueFlag: 1 })).toEqual(['格式', '唯一']);
  });

  it('字典来源单独展示；警告级只在存在可降级规则时提示', () => {
    expect(describeColumnRules({ fieldType: 2, dictType: 'sex', ruleSeverity: 2 })).toEqual(['字典 sex']);
    expect(describeColumnRules({ fieldType: 5, maxValue: '9', ruleSeverity: 2 })).toEqual(['范围 -∞ ~ 9', '警告级']);
  });
});
