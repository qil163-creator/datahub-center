import { describe, expect, it } from 'vitest';

import { setRulePrefill, suggestionToRule, takeRulePrefill } from './prefill';

describe('quality rule prefill', () => {
  it('maps a profiling suggestion into rule form values', () => {
    const rule = suggestionToRule(
      {
        columnName: 'status',
        params: { values: ['A', 'B'] },
        qualityDim: 'NORMALIZATION',
        reason: '取值只有 2 种',
        ruleName: 'status 枚举校验',
        ruleType: 'ENUM',
        strategyKey: 'enum_in',
      },
      'ods.orders',
    );
    expect(rule.ruleType).toBe('ENUM');
    expect(rule.checkLevel).toBe('FIELD');
    expect(JSON.parse(rule.paramSchema!)).toEqual({ values: ['A', 'B'] });
    expect(rule.description).toContain('ods.orders.status');
  });

  it('hands the prefill over exactly once', () => {
    setRulePrefill({ ruleName: 'x' });
    expect(takeRulePrefill()?.ruleName).toBe('x');
    expect(takeRulePrefill()).toBeUndefined();
  });
});
