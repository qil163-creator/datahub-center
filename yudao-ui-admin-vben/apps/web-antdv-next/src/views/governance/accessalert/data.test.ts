import { describe, expect, it } from 'vitest';

import { keyRefFormError } from '../keyref/data';
import { formatTime, ruleLabel, ruleUnit, statusView } from './data';

describe('access alert and key ref page data', () => {
  it('labels known codes and echoes unknown ones', () => {
    expect(ruleLabel('PLAINTEXT')).toBe('豁免明文访问过多');
    expect(ruleUnit('TABLE_SPREAD')).toBe('张表');
    expect(ruleLabel('NEW_RULE')).toBe('NEW_RULE');
    expect(statusView('OPEN').label).toBe('待处理');
    expect(statusView('LATER').label).toBe('LATER');
  });

  it('formats timestamps for display only', () => {
    expect(formatTime(undefined)).toBe('-');
    expect(formatTime(new Date(2026, 10, 12, 9, 5).getTime())).toBe(
      '2026-11-12 09:05',
    );
    expect(formatTime('not a date')).toBe('not a date');
  });

  it('validates key reference codes like the backend', () => {
    expect(keyRefFormError('pii_default', '个人信息')).toBeUndefined();
    expect(keyRefFormError('1bad', 'x')).toContain('字母开头');
    expect(keyRefFormError('ok', '  ')).toBe('请输入名称');
  });
});
