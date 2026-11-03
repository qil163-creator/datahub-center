import { describe, expect, it } from 'vitest';

import { buildQueryString } from './query';

describe('supply query encoding', () => {
  it('encodes chinese, space, ampersand and plus', () => {
    expect(buildQueryString({ q: '测 &+' })).toBe('q=%E6%B5%8B%20%26%2B');
  });

  it('keeps exact decimal strings', () => {
    expect(buildQueryString({ amount: '1.2300', id: '9007199254740993' })).toBe(
      'amount=1.2300&id=9007199254740993',
    );
  });
});
