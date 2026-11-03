import type { QualityTaskApi } from '#/api/governance/quality/task';

import { describe, expect, it } from 'vitest';

import {
  displayRuleCatalog,
  newEvaluation,
  newTask,
  taskPayload,
  validateEvaluation,
} from './data';

function evaluation(strategyKey = 'CHARACTER_VALIDATION') {
  return {
    ...newEvaluation({
      ruleCode: '107',
      ruleName: '字段字符类型校验',
      strategyKey,
      qualityDim: 'ACCURACY',
      description: '',
      enabled: true,
      sampleSupported: true,
    }),
    evaluationName: '字段检查',
    objectKey: '9007199254740993',
    columnNames: ['SCENECD'],
  };
}

describe('quality task aggregate form', () => {
  it.each(['COMPOSITE_UNIQUENESS_VALIDATION', 'GROUP_FIELD_COMPLETENESS'])(
    '数据元 %s 拒绝不足两个字段并接受多字段配置',
    (strategyKey) => {
      expect(validateEvaluation(evaluation(strategyKey), 'standard')).toContain(
        '两个',
      );
      expect(
        validateEvaluation(
          { ...evaluation(strategyKey), columnNames: ['order_no', 'order_date'] },
          'standard',
        ),
      ).toBeUndefined();
    },
  );
  it('adds only disabled reference cards to the ten executable backend strategies', () => {
    const rules = [
      ['107', 'ACCURACY'],
      ['306', 'CONSISTENCY'],
      ['311', 'CONSISTENCY'],
      ['205', 'CONSISTENCY'],
      ['115', 'COMPLETENESS'],
      ['118', 'COMPLETENESS'],
      ['108', 'COMPLETENESS'],
      ['110', 'COMPLETENESS'],
      ['111', 'COMPLETENESS'],
      ['112', 'COMPLETENESS'],
    ].map(([ruleCode, qualityDim]) => ({
      ruleCode: ruleCode!,
      qualityDim: qualityDim!,
      strategyKey: '',
      ruleName: '',
      description: '',
      enabled: true,
      sampleSupported: false,
    }));
    const catalog = displayRuleCatalog(rules);
    expect(catalog).toHaveLength(28);
    expect(catalog.filter((rule) => rule.enabled)).toHaveLength(10);
    expect(
      [
        'ACCURACY',
        'NORMALIZATION',
        'TIMELINESS',
        'CONSISTENCY',
        'COMPLETENESS',
      ].map(
        (dimension) =>
          catalog.filter((rule) => rule.qualityDim === dimension).length,
      ),
    ).toEqual([5, 3, 1, 7, 12]);
  });
  it('preserves lossless identifiers and exact decimal strings through the atomic payload', () => {
    const task: QualityTaskApi.Task = {
      ...newTask(),
      id: '9223372036854775806',
      ownerUserId: '9007199254740995',
      objects: [
        {
          id: '9007199254740993',
          clientKey: '9007199254740993',
          objectName: '检查对象',
          datasourceConnId: '9007199254740997',
          tableName: 'quality_data',
        },
      ],
      evaluations: [
        {
          ...evaluation('NUMERIC_RANGE_VALIDATION'),
          config: {
            minValue: '9007199254740993.000000000000000001',
            maxValue: '9007199254740993.000000000000000002',
            includeBoundary: true,
          },
        },
      ],
      objectIds: [3],
      ruleIds: [4],
    };
    const result = taskPayload(task);
    expect(result.id).toBe('9223372036854775806');
    expect(result.objects?.[0]?.datasourceConnId).toBe('9007199254740997');
    expect(result.evaluations?.[0]?.config.minValue).toBe(
      '9007199254740993.000000000000000001',
    );
    expect(result.objectIds).toBeUndefined();
    expect(result.ruleIds).toBeUndefined();
    expect(task.objectIds).toEqual([3]);
    expect(result).not.toBe(task);
  });
  it('does not send time conditions for unrelated rules', () => {
    expect(evaluation().config.conditions).toBeUndefined();
    expect(evaluation('TIME_ORDER_VALIDATION').config.conditions).toEqual([
      { leftField: '', operator: '<', rightField: '' },
    ]);
  });
  it('requires configured character types, complete time fields and multiple grouped fields', () => {
    expect(
      validateEvaluation({ ...evaluation(), config: { allowedTypes: [] } }),
    ).toContain('允许字符');
    expect(validateEvaluation(evaluation('TIME_ORDER_VALIDATION'))).toContain(
      '时间顺序',
    );
    expect(
      validateEvaluation(evaluation('GROUP_FIELD_COMPLETENESS')),
    ).toContain('两个');
    expect(
      validateEvaluation({
        ...evaluation('GROUP_FIELD_COMPLETENESS'),
        columnNames: ['a', 'b'],
      }),
    ).toBeUndefined();
    expect(
      validateEvaluation({
        ...evaluation('MUTUAL_EXCLUSION_VALIDATION'),
        columnNames: ['', 'b'],
      }),
    ).toContain('检查字段');
    expect(
      validateEvaluation({
        ...evaluation('MUTUAL_EXCLUSION_VALIDATION'),
        columnNames: ['a', 'a'],
      }),
    ).toContain('不能重复');
  });
  it('accepts exact decimal boundaries but rejects non-decimal text and missing true values', () => {
    expect(
      validateEvaluation({
        ...evaluation('NUMERIC_RANGE_VALIDATION'),
        config: { minValue: '99999999999999999999.999999999999' },
      }),
    ).toBeUndefined();
    expect(
      validateEvaluation({
        ...evaluation('NUMERIC_RANGE_VALIDATION'),
        config: { minValue: '1e8' },
      }),
    ).toContain('十进制');
    expect(
      validateEvaluation({
        ...evaluation('MUTUAL_EXCLUSION_VALIDATION'),
        columnNames: ['a', 'b'],
        config: { condition: 'IS_TRUE', trueValues: ['1', ''] },
      }),
    ).toContain('TRUE');
  });
  it('requires a selected code table or complete manual values, and a reference target', () => {
    expect(
      validateEvaluation({
        ...evaluation('ENUM_VALIDATION'),
        config: { useCodeTable: true, codeSetId: '9007199254740993' },
      }),
    ).toBeUndefined();
    expect(
      validateEvaluation({
        ...evaluation('ENUM_VALIDATION'),
        config: { useCodeTable: false, validValues: [''] },
      }),
    ).toContain('枚举值');
    expect(validateEvaluation(evaluation('FOREIGN_KEY_VALIDATION'))).toContain(
      '引用字段',
    );
  });
});
