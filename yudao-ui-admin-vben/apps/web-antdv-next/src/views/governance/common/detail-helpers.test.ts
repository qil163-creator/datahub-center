import { CommonStatusEnum } from '@vben/constants';

import { describe, expect, it, vi } from 'vitest';

import {
  auditDetailFields,
  formatEnableStatus,
  formatJsonText,
  prettyJson,
} from './detail-helpers';

vi.mock('#/api/system/user', () => ({
  getSimpleUserList: () => Promise.resolve([]),
}));

describe('formatEnableStatus', () => {
  it('maps enable and disable to chinese labels', () => {
    expect(formatEnableStatus(CommonStatusEnum.ENABLE)).toBe('启用');
    expect(formatEnableStatus(CommonStatusEnum.DISABLE)).toBe('停用');
    expect(formatEnableStatus(undefined)).toBe('-');
  });
});

describe('formatJsonText', () => {
  it('pretty-prints json and falls back for empty or invalid text', () => {
    expect(formatJsonText('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(formatJsonText('not-json')).toBe('not-json');
    expect(formatJsonText()).toBe('-');
    expect(prettyJson('')).toBe('');
  });
});

describe('auditDetailFields', () => {
  it('always returns creator updater and timestamps', () => {
    const fields = auditDetailFields({
      creator: '1',
      createTime: undefined,
      updater: '',
      updateTime: undefined,
    });
    expect(fields.map((item) => item.label)).toEqual([
      '创建人',
      '创建时间',
      '更新人',
      '更新时间',
    ]);
  });
});
