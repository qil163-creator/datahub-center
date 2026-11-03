import { describe, expect, it } from 'vitest';

import {
  cleanParameterFields,
  cleanParameterIssues,
  cleanParameterStrategyKeys,
  cleanParameterValue,
  replaceCleanParameter,
} from './clean-rule-parameters';

describe('清洗函数业务表单契约', () => {
  it('仅提供当前十七种函数的参数，ETL 字段绑定不会进入治理参数', () => {
    for (const strategy of [
      'ADD_PREFIX_SUFFIX',
      'CHECK_EXPIRATION',
      'DATE_FILL_NULL',
      'DATE_FORMAT_STD',
      'FIX_TO_PRECISION',
      'KEEP_LATEST_OR_FIRST',
      'MENU_CUSTOM',
      'PHONE_FORMAT_STD',
      'PK_DEDUP_FIRST',
      'REMOVE_EMPTY_COMBINATION',
      'REMOVE_WHITESPACE',
      'SIMPLE_REPLACE',
      'STRING_SUBSTR',
      'TO_LOWERCASE',
      'TO_UPPERCASE',
      'VALUE_REPLACE',
      'WITHIN_BOUNDARY',
    ]) {
      expect(cleanParameterFields(strategy)).toBeDefined();
      expect(
        cleanParameterFields(strategy)!.some(
          (field) => field.key === 'columns',
        ),
      ).toBe(false);
    }
    expect(cleanParameterFields('HISTORICAL')).toBeUndefined();
    expect(cleanParameterFields('__proto__')).toBeUndefined();
    expect(cleanParameterIssues('HISTORICAL', {})).toEqual([]);
    expect(cleanParameterIssues('HISTORICAL', { min: '1' })).toContain(
      '历史或未知策略只能保留空参数',
    );
    expect(
      cleanParameterIssues('TO_LOWERCASE', { columns: ['name'] })[0],
    ).toContain('未知字段');
  });
  it('完整保留大整数小数文本，拒绝已被数字解析的值', () => {
    const exact = '9007199254740993.123456789012345678901';
    const config = replaceCleanParameter(
      { max: '9007199254740994' },
      'min',
      exact,
    );
    expect(config.min).toBe(exact);
    expect(cleanParameterIssues('WITHIN_BOUNDARY', config)).toEqual([]);
    expect(
      cleanParameterIssues('WITHIN_BOUNDARY', { min: 0, max: '1' }),
    ).not.toEqual([]);
  });
  it('缺省值仅显示提示，显式清空后仍保持缺省与空串的区别', () => {
    expect(
      replaceCleanParameter({ min: '1', max: '2' }, 'min', undefined),
    ).toEqual({ max: '2' });
    expect(replaceCleanParameter({}, 'replacement', '')).toEqual({
      replacement: '',
    });
    expect(
      cleanParameterIssues('SIMPLE_REPLACE', { regex: 'x', replacement: '' }),
    ).toEqual([]);
    expect(cleanParameterIssues('FIX_TO_PRECISION', {})).toEqual([]);
    expect(cleanParameterIssues('STRING_SUBSTR', {})).toContain(
      '最大长度：请填写',
    );
    expect(cleanParameterIssues('FIX_TO_PRECISION', { scale: 13 })).not.toEqual(
      [],
    );
  });
  it('编辑旧精度参数将别名转换为正式参数，不改动其余历史字段', () => {
    expect(
      cleanParameterValue({ stringValue: '3' }, 'scale', 'FIX_TO_PRECISION'),
    ).toBe('3');
    expect(replaceCleanParameter({ stringValue: '3' }, 'scale', 4)).toEqual({
      scale: 4,
    });
    expect(replaceCleanParameter({ oldField: 'keep' }, 'min', '1')).toEqual({
      oldField: 'keep',
      min: '1',
    });
  });
  it('固定日期填充和过期截止方式遵循后端条件参数', () => {
    expect(
      cleanParameterIssues('DATE_FILL_NULL', { fillType: 'FIXED' }),
    ).toContain('固定填充值：请填写');
    expect(
      cleanParameterIssues('DATE_FILL_NULL', {
        fillType: 'CURRENT',
        value: '',
      }),
    ).toEqual([]);
    expect(
      cleanParameterFields('CHECK_EXPIRATION', { dataRange: 1 })!.find(
        (field) => field.key === 'dataRangeValue',
      )?.kind,
    ).toBe('text');
    expect(
      cleanParameterIssues('CHECK_EXPIRATION', { dataRange: 1 }),
    ).toContain('截止日期：请填写');
    expect(
      cleanParameterIssues('CHECK_EXPIRATION', {
        dataRange: 0,
        dataRangeValue: '30',
      }),
    ).toEqual([]);
  });
  it('枚举映射和去重排序限制字段、重复值和容量', () => {
    expect(
      cleanParameterIssues('MENU_CUSTOM', {
        stringValue: [{ value: '', name: '' }],
      }),
    ).toEqual([]);
    expect(
      cleanParameterIssues('MENU_CUSTOM', {
        stringValue: [
          { value: '1', name: '启用' },
          { value: '1', name: '重复' },
        ],
      }),
    ).not.toEqual([]);
    expect(
      cleanParameterIssues('KEEP_LATEST_OR_FIRST', {
        stringValue: [{ columns: 'time', type: 0, sort: 1 }],
      }),
    ).toEqual([]);
    expect(
      cleanParameterIssues('KEEP_LATEST_OR_FIRST', {
        stringValue: [{ columns: 'time', type: 0, sort: 1, extra: true }],
      }),
    ).not.toEqual([]);
    expect(
      cleanParameterIssues('DATE_FORMAT_STD', {
        inputFormats: Array.from({ length: 17 }, () => 'yyyy-MM-dd'),
      }),
    ).not.toEqual([]);
  });

  it('四十种清洗算法均有实际参数定义且不混入ETL字段绑定', () => {
    expect(cleanParameterStrategyKeys()).toHaveLength(40);
    for (const key of cleanParameterStrategyKeys())
      expect(
        cleanParameterFields(key)?.some((field) => field.key === 'columns'),
      ).toBe(false);
    expect(
      cleanParameterFields('UNIT_CONVERSION')?.map((field) => field.key),
    ).toEqual(['factor', 'offset', 'scale']);
    expect(
      cleanParameterFields('DEDUP_BY_PRIORITY')?.map((field) => field.key),
    ).toEqual(['priorityField', 'priorityValues', 'timeField', 'inputFormats']);
  });

  it('转换、填充和修复参数保持精确数值、布尔值和显式null', () => {
    expect(
      cleanParameterIssues('UNIT_CONVERSION', {
        factor: '9007199254740993.00000000000001',
        offset: '0',
        scale: 12,
      }),
    ).toEqual([]);
    expect(
      cleanParameterIssues('UNIT_CONVERSION', { factor: 123 }),
    ).not.toEqual([]);
    expect(cleanParameterIssues('BOOLEAN_FILL_NULL', { value: false })).toEqual(
      [],
    );
    expect(
      cleanParameterIssues('BOOLEAN_FILL_NULL', { value: 'false' }),
    ).not.toEqual([]);
    const config = replaceCleanParameter({}, 'replacement', null);
    expect(config).toEqual({ replacement: null });
    expect(cleanParameterIssues('INVALID_NUMBER_REPLACE', config)).toEqual([]);
    expect(cleanParameterIssues('ZERO_PAD', { length: 0 })).not.toEqual([]);
  });

  it('集合参数和条件必填参数可编辑且遵循各自容量', () => {
    expect(
      cleanParameterIssues('DEDUP_PREFER_NON_NULL', {
        preferredFields: Array.from({ length: 32 }, (_, i) => `f${i}`),
      }),
    ).toEqual([]);
    expect(
      cleanParameterIssues('DEDUP_BY_PRIORITY', {
        priorityField: 'source',
        priorityValues: [],
      }),
    ).not.toEqual([]);
    expect(
      cleanParameterIssues('FIX_FUTURE_DATE', { replacement: 'BOUNDARY' }),
    ).toContain('指定日期边界：请填写');
    expect(cleanParameterIssues('TIME_FILL_NULL', { mode: 'FIXED' })).toContain(
      '固定填充时间：请填写',
    );
    expect(
      cleanParameterIssues('KEYWORD_MASK', { keywords: ['敏感'], mask: '😀' }),
    ).toEqual([]);
    expect(
      cleanParameterIssues('KEYWORD_MASK', { keywords: ['敏感'], mask: 'ab' }),
    ).not.toEqual([]);
  });
});
