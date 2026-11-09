import { describe, expect, it } from 'vitest';

import { inventoryCards } from './model';

const report = {
  activeUserCount: 3,
  apiCallTotal: 2500,
  applyTotal: 1,
  browseTotal: 4,
  categoryDistribution: [],
  levelDistribution: { IDLE: 1, LOW: 2 },
  longestIdle: [],
  offlineCount: 0,
  publishedCount: 3,
  statDate: '2026-11-09',
  suggestionDistribution: {},
  topHot: [],
  topValue: [],
  totalCount: 4,
  typeDistribution: { '1': 3, '2': 1 },
  unpublishedCount: 1,
  usageTotal: 2,
  usedCount: 1,
  windowDays: 30,
};

describe('inventory cards', () => {
  it('summarises totals, usage share and idle assets', () => {
    const cards = inventoryCards(report);
    expect(cards.map((card) => card.key)).toEqual([
      'total',
      'used',
      'idle',
      'users',
      'usage',
      'api',
    ]);
    expect(cards[0]?.hint).toBe('已发布 3 · 未发布 1 · 已下线 0');
    expect(cards[1]?.hint).toContain('约占已发布 33%');
    expect(cards[2]?.value).toBe(1);
    expect(cards[4]?.value).toBe(3);
  });

  it('avoids dividing by zero when nothing is published', () => {
    const cards = inventoryCards({
      ...report,
      publishedCount: 0,
      usedCount: 0,
      levelDistribution: {},
    });
    expect(cards[1]?.hint).toContain('约占已发布 0%');
    expect(cards[2]?.value).toBe(0);
  });
});
