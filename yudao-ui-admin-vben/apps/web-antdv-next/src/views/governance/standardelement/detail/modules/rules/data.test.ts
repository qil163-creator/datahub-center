import { describe, expect, it } from 'vitest';

import {
  editableEvaluation,
  newEvaluation,
  validateEvaluation,
} from '../../../../quality/task/data';

function rule(strategyKey: string) {
  return {
    ...newEvaluation({
      ruleCode: '107',
      strategyKey,
      ruleName: '规则',
      qualityDim: '',
      description: '',
      enabled: true,
      sampleSupported: false,
    }),
    evaluationName: '数据元规则',
  };
}

describe('数据元规则配置', () => {
  it('数据元单字段规则无需任务对象，任务调用仍要求对象', () => {
    const value = rule('CHARACTER_VALIDATION');
    expect(validateEvaluation(value, 'standard')).toBeUndefined();
    expect(validateEvaluation(value)).toContain('评测对象');
  });

  it('跨字段规则仍要求完整且不同的字段，引用规则要求两端配置', () => {
    expect(
      validateEvaluation(rule('GROUP_FIELD_COMPLETENESS'), 'standard'),
    ).toContain('两个');
    expect(
      validateEvaluation(rule('TIME_ORDER_VALIDATION'), 'standard'),
    ).toContain('时间顺序');
    const mutex = {
      ...rule('MUTUAL_EXCLUSION_VALIDATION'),
      columnNames: ['a', 'a'],
    };
    expect(validateEvaluation(mutex, 'standard')).toContain('不能重复');
    const reference = {
      ...rule('FOREIGN_KEY_VALIDATION'),
      objectKey: '9007199254740993',
      columnNames: ['source'],
    };
    expect(validateEvaluation(reference, 'standard')).toContain('引用字段');
    reference.config = {
      referenceObjectKey: '9007199254740995',
      refColumn: 'target',
    };
    expect(validateEvaluation(reference, 'standard')).toBeUndefined();
    expect(
      validateEvaluation({ ...reference, columnNames: [] }, 'standard'),
    ).toContain('检查字段');
  });

  it('编辑只含枚举值的配置时补齐显示行，并且不改变原配置', () => {
    const value = {
      ...rule('ENUM_VALIDATION'),
      config: {
        useCodeTable: false,
        validValues: ['A', 'B'],
        valueLabels: [{ value: 'A', label: '甲' }],
      },
    };
    const editable = editableEvaluation(value);
    expect(editable.config.valueLabels).toEqual([
      { value: 'A', label: '甲' },
      { value: 'B', label: 'B' },
    ]);
    expect(value.config.valueLabels).toHaveLength(1);
    expect(validateEvaluation(editable, 'standard')).toBeUndefined();
  });

  it('精确数值边界以十进制字符串校验和保留', () => {
    const minValue = '9007199254740993.000000000000000001';
    const value = { ...rule('NUMERIC_RANGE_VALIDATION'), config: { minValue } };
    expect(validateEvaluation(value, 'standard')).toBeUndefined();
    expect(editableEvaluation(value).config.minValue).toBe(minValue);
    expect(
      validateEvaluation({ ...value, config: { minValue: '1e8' } }, 'standard'),
    ).toContain('十进制');
  });
});
