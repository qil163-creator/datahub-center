import { describe, expect, it } from 'vitest';

import { createLatestWins, parseResourceId, resourceIdKey } from './resource-id';

describe('parseResourceId', () => {
  it('keeps large decimal IDs as strings', () => {
    expect(parseResourceId('9223372036854775807')).toBe('9223372036854775807');
    expect(parseResourceId(123)).toBe('123');
  });

  it('rejects unsafe numbers and zero', () => {
    expect(parseResourceId(Number.MAX_SAFE_INTEGER + 1)).toBeUndefined();
    expect(parseResourceId('0')).toBeUndefined();
    expect(parseResourceId(['1'])).toBeUndefined();
  });
});

describe('createLatestWins', () => {
  it('only the latest ticket remains current', () => {
    const guard = createLatestWins();
    const first = guard.next();
    const second = guard.next();
    expect(first.isCurrent()).toBe(false);
    expect(second.isCurrent()).toBe(true);
    guard.invalidate();
    expect(second.isCurrent()).toBe(false);
  });

  it('normalizes map keys without numeric coercion', () => {
    expect(resourceIdKey('9223372036854775807')).toBe('9223372036854775807');
  });
});
