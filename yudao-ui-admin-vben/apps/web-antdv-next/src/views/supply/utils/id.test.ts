import { describe, expect, it } from 'vitest';

import { sameId, toIdString } from './id';

describe('supply lossless id', () => {
  it('keeps integers outside javascript safe range', () => {
    expect(toIdString('9007199254740993')).toBe('9007199254740993');
    expect(sameId('9007199254740993', '9007199254740993')).toBe(true);
    expect(sameId(9007199254740993, '9007199254740993')).toBe(false);
  });
});
