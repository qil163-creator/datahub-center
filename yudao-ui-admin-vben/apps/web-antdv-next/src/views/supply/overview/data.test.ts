import { describe, expect, it } from 'vitest';

import { buildRankList } from './data';

describe('buildRankList', () => {
  it('keeps same-name APIs distinct by lossless apiId', () => {
    const list = buildRankList([
      { apiId: '9007199254740993', name: '站点服务', value: 10 },
      { apiId: 11, name: '站点服务', value: 3 },
    ]);
    expect(list).toHaveLength(2);
    expect(list[0]?.apiId).toBe('9007199254740993');
    expect(list[1]?.apiId).toBe('11');
  });
});
