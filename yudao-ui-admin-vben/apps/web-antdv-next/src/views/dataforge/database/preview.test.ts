import { describe, expect, it } from 'vitest';

import {
  displayText,
  formatPreviewCell,
  resolvePreviewError,
  tableTypeLabel,
  yesNo,
} from './preview';

describe('tableTypeLabel', () => {
  it('maps physical tables to BASE TABLE', () => {
    expect(tableTypeLabel(false)).toBe('BASE TABLE');
    expect(tableTypeLabel()).toBe('BASE TABLE');
  });

  it('maps views to VIEW', () => {
    expect(tableTypeLabel(true)).toBe('VIEW');
  });
});

describe('displayText', () => {
  it('uses dash for empty values', () => {
    expect(displayText()).toBe('-');
    expect(displayText('  ')).toBe('-');
    expect(displayText('用户表')).toBe('用户表');
  });
});

describe('yesNo', () => {
  it('renders boolean flags', () => {
    expect(yesNo(true)).toBe('是');
    expect(yesNo(false)).toBe('否');
    expect(yesNo()).toBe('-');
  });
});

describe('formatPreviewCell', () => {
  it('keeps zero and false visible', () => {
    expect(formatPreviewCell(0)).toBe('0');
    expect(formatPreviewCell(false)).toBe('false');
    expect(formatPreviewCell(null)).toBe('-');
    expect(formatPreviewCell('')).toBe('-');
  });
});

describe('resolvePreviewError', () => {
  it('keeps business messages and replaces the generic system error', () => {
    expect(resolvePreviewError(new Error('数据库连接失败：Connection refused'))).toBe(
      '数据库连接失败：Connection refused',
    );
    expect(resolvePreviewError(new Error('系统异常'))).toContain('无法连接该数据库');
    expect(resolvePreviewError({})).toContain('无法连接该数据库');
  });
});
