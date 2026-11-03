import { describe, expect, it, vi } from 'vitest';

import { getUserNickname } from './user';

vi.mock('#/api/system/user', () => ({
  getSimpleUserList: () => Promise.resolve([]),
}));

describe('getUserNickname', () => {
  it('keeps unsafe-range ids as decimal text instead of Number()', () => {
    expect(getUserNickname('9007199254740993')).toBe('9007199254740993');
  });
});
