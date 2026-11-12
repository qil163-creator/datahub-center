import { describe, expect, it } from 'vitest';

import {
  formatSuggestionParams,
  isActiveRun,
  maskReasonLabel,
  ratioToPercent,
  ratioWidth,
} from './helpers';

describe('数据探查展示辅助', () => {
  it('十进制比率转百分比不经过浮点', () => {
    expect(ratioToPercent('0.012500')).toBe('1.25%');
    expect(ratioToPercent('1.000000')).toBe('100%');
    expect(ratioToPercent('0.000000')).toBe('0%');
    expect(ratioToPercent('0.333335')).toBe('33.33%');
    expect(ratioToPercent('0.333355')).toBe('33.34%');
    expect(ratioToPercent('0.999999')).toBe('100%');
    expect(ratioToPercent('0.1')).toBe('10%');
    expect(ratioToPercent('0.123456', 0)).toBe('12%');
  });

  it('非法比率显示为空', () => {
    expect(ratioToPercent(undefined)).toBe('-');
    expect(ratioToPercent(null)).toBe('-');
    expect(ratioToPercent('1e-3')).toBe('-');
    expect(ratioToPercent('-0.1')).toBe('-');
    expect(ratioToPercent('abc')).toBe('-');
  });

  it('分布条宽度限制在 0~100%', () => {
    expect(ratioWidth('0.600000')).toBe('60%');
    expect(ratioWidth('1.000000')).toBe('100%');
    expect(ratioWidth('2.5')).toBe('100%');
    expect(ratioWidth(undefined)).toBe('0%');
  });

  it('只有等待或执行中的记录需要轮询', () => {
    expect(isActiveRun({ id: 1, metaTableId: 1, status: 'PENDING' })).toBe(true);
    expect(isActiveRun({ id: 1, metaTableId: 1, status: 'RUNNING' })).toBe(true);
    expect(isActiveRun({ id: 1, metaTableId: 1, status: 'SUCCESS' })).toBe(false);
    expect(isActiveRun({ id: 1, metaTableId: 1, status: 'FAILED' })).toBe(false);
    expect(isActiveRun(null)).toBe(false);
  });

  it('脱敏原因有可读文本，未知原因原样显示', () => {
    expect(maskReasonLabel('RULE')).toBe('命中数据分类规则');
    expect(maskReasonLabel('MASK_FAILED')).toContain('未保存取值');
    expect(maskReasonLabel(null)).toBe('取值已脱敏');
    expect(maskReasonLabel('NEW_REASON')).toBe('NEW_REASON');
  });

  it('参数按 JSON 展示', () => {
    expect(formatSuggestionParams({ column: 'a', allowBlank: true })).toBe(
      '{"column":"a","allowBlank":true}',
    );
  });
});
