import { describe, expect, it } from 'vitest';

import {
  auditParameterDefaults,
  auditParameterFields,
  auditParameterIssue,
  auditReferencedColumns,
  pruneAuditConfig,
} from './audit-rule-parameters';

describe('完整稽查参数契约', () => {
  it('日期、占位值、脱敏和元信息提供真实参数表单', () => {
    expect(
      auditParameterFields('DATE_FORMAT_VALIDATION').map((row) => row.key),
    ).toEqual(['pattern', 'invalidValues', 'regex', 'ignoreNullValue']);
    expect(auditParameterIssue('PLACEHOLDER_VALIDATION', { values: [''] })).toBeUndefined();
    expect(auditParameterDefaults('COLUMN_METADATA_COMPLETENESS')).toEqual({
      requiredProperties: ['name', 'type', 'comment'],
    });
    expect(
      auditParameterIssue('SENSITIVE_MASK_VALIDATION', { maskType: 'CUSTOM' }),
    ).toContain('正则');
    expect(
      auditParameterIssue('MODEL_STRUCTURE_VALIDATION', {
        expectedColumns: [{ name: 'id' }, { name: 'id' }],
      }),
    ).toContain('重复');
  });
  it('异常值和状态断言只接受精确十进制字符串', () => {
    expect(
      auditParameterIssue('NUMERIC_OUTLIER_VALIDATION', {
        method: 'RANGE',
        minValue: '9007199254740993.123456789',
      }),
    ).toBeUndefined();
    expect(
      auditParameterIssue('NUMERIC_OUTLIER_VALIDATION', {
        method: 'RANGE',
        minValue: 1,
      }),
    ).toContain('字符串');
    expect(
      auditParameterIssue('STATE_CONSISTENCY_VALIDATION', {
        conditionColumn: 'status',
        conditionValues: ['1'],
        assertions: [
          {
            column: 'amount',
            operator: 'GT',
            valueType: 'DECIMAL',
            value: '9007199254740993.123',
          },
        ],
      }),
    ).toBeUndefined();
    expect(
      auditParameterIssue('STATE_CONSISTENCY_VALIDATION', {
        conditionColumn: 'status',
        conditionValues: ['1'],
        assertions: [
          { column: 'amount', operator: 'GT', valueType: 'DECIMAL', value: 1 },
        ],
      }),
    ).toContain('比较值');
  });
  it('自动把关系、条件与跨表比较左字段纳入授权字段集合', () => {
    expect(
      auditReferencedColumns(
        'NUMERIC_RELATION_VALIDATION',
        {
          conditions: [
            { leftField: 'start', rightField: 'end', operator: '<' },
          ],
        },
        [],
      ),
    ).toEqual(['start', 'end']);
    expect(
      auditReferencedColumns(
        'CONDITIONAL_REQUIRED_VALIDATION',
        { conditionColumn: 'status', requiredColumns: ['amount', 'status'] },
        [],
      ),
    ).toEqual(['status', 'amount']);
    expect(
      auditReferencedColumns(
        'CROSS_TABLE_CONSISTENCY',
        {
          leftColumn: 'id',
          comparisons: [{ leftColumn: 'value', rightColumn: 'remote_value' }],
        },
        ['id'],
      ),
    ).toEqual(['id', 'value']);
    expect(auditReferencedColumns('RECORD_COMPLETENESS_VALIDATION', {}, ['client_subset'])).toEqual([]);
  });
});

describe('表行数波动检测参数', () => {
  it('动态阈值展示自身参数并校验灵敏度与样本数', () => {
    const keys = auditParameterFields('ROW_COUNT_FLUCTUATION', {
      compareMode: 'DYNAMIC',
    }).map((field) => field.key);
    expect(keys).toEqual([
      'compareMode',
      'dynamicMethod',
      'sensitivity',
      'historySize',
      'minSamples',
      'sameWeekday',
      'minRowCount',
      'maxRowCount',
    ]);
    expect(
      auditParameterIssue('ROW_COUNT_FLUCTUATION', { compareMode: 'DYNAMIC' }),
    ).toBeUndefined();
    expect(
      auditParameterIssue('ROW_COUNT_FLUCTUATION', {
        compareMode: 'DYNAMIC',
        sensitivity: '11',
      }),
    ).toContain('灵敏度');
    expect(
      auditParameterIssue('ROW_COUNT_FLUCTUATION', {
        compareMode: 'DYNAMIC',
        historySize: 5,
        minSamples: 6,
      }),
    ).toContain('最少样本数');
    expect(
      auditParameterIssue('ROW_COUNT_FLUCTUATION', { compareMode: 'WEEK' }),
    ).toContain('至少配置');
  });

  it('切换对比方式后只提交当前方式的参数', () => {
    expect(
      pruneAuditConfig('ROW_COUNT_FLUCTUATION', {
        compareMode: 'DYNAMIC',
        maxDecreasePercent: '30',
        windowHours: 12,
        sensitivity: '3',
        minRowCount: '',
      }),
    ).toEqual({ compareMode: 'DYNAMIC', sensitivity: '3' });
    expect(
      pruneAuditConfig('ROW_COUNT_FLUCTUATION', {
        compareMode: 'WEEK',
        sensitivity: '3',
        maxDecreasePercent: '30',
        windowHours: 6,
      }),
    ).toEqual({ compareMode: 'WEEK', maxDecreasePercent: '30', windowHours: 6 });
    const other = { minValue: '1' };
    expect(pruneAuditConfig('NUMERIC_RANGE_VALIDATION', other)).toBe(other);
  });
});
