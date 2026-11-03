import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { compareEtlVersions } from './version';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));

describe('etl immutable version comparison API', () => {
  it('passes both immutable version IDs without numeric conversion', async () => {
    const params = {
      sourceVersionId: '9007199254740993',
      targetVersionId: '9007199254740995',
    };
    await compareEtlVersions(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/version/compare',
      { params },
    );
  });
});
