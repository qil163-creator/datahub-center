import { describe, expect, it } from 'vitest';

import { createLatestGuard } from './latest';

describe('latest-wins guard', () => {
  it('only the newest request is current', () => {
    const guard = createLatestGuard();
    const first = guard.next();
    const second = guard.next();
    expect(first()).toBe(true);
    expect(second()).toBe(false);
    guard.invalidate();
    expect(second()).toBe(true);
  });
});
