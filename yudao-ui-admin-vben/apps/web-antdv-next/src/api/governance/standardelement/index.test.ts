import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { bindStandardElementCodeSet } from './index';

vi.mock('#/api/request', () => ({
  requestClient: { put: vi.fn() },
}));

describe('标准数据元码表绑定接口', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each<[number | string, number | string]>([
    ['2147483646', '2147483647'],
    [1, 2],
  ])('原样传递整数或十进制字符串 ID：%s / %s', async (id, codeSetId) => {
    vi.mocked(requestClient.put).mockResolvedValue(true);

    await expect(bindStandardElementCodeSet(id, codeSetId)).resolves.toBe(true);

    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/standard-element/bind-code-set',
      undefined,
      { params: { id, codeSetId } },
    );
  });
});
