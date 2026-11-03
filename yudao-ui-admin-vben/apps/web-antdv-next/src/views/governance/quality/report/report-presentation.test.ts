import { describe, expect, it } from 'vitest';

import {
  chartScore,
  dimensionCards,
  dimensionLabel,
  percentWidth,
  reportStatus,
} from './report-presentation';

describe('质量报告展示', () => {
  it('未配置或未执行的分数保留未知值，不能画成零分', () => {
    expect(chartScore(null)).toBeNull();
    expect(chartScore(undefined)).toBeNull();
    expect(chartScore('')).toBeNull();
    expect(chartScore('0')).toBe(0);
    expect(chartScore('100')).toBe(100);
  });
  it('精确小数只在图表坐标近似，原始统计文本不被改写', () => {
    const ratio = '12.34567890123456789';
    const cards = dimensionCards([
      {
        qualityDim: 'ACCURACY',
        ruleCount: 1,
        totalCount: '9007199254740993',
        issueCount: 1,
        problemRatio: ratio,
        score: '87.65432109876543211',
        previousProblemRatio: null,
      },
    ]);
    expect(cards[0]?.data?.problemRatio).toBe(ratio);
    expect(cards[0]?.data?.totalCount).toBe('9007199254740993');
    expect(percentWidth('120')).toBe('100%');
  });
  it('保留五个参考维度，并显示实际返回的其他维度', () => {
    const cards = dimensionCards([
      {
        qualityDim: 'UNIQUENESS',
        ruleCount: 1,
        totalCount: 10,
        issueCount: 0,
        problemRatio: '0',
        score: '100',
        previousProblemRatio: null,
      },
    ]);
    expect(cards.map((item) => item.label)).toEqual([
      '准确性',
      '规范性',
      '时效性',
      '一致性',
      '完整性',
      '唯一性',
    ]);
    expect(dimensionLabel('CUSTOM')).toBe('CUSTOM');
    expect(reportStatus('UNKNOWN')).toBe('结果待确认');
  });
});
