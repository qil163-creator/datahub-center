import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getStandardAssociations } from './association';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn() },
}));

const availabilityPath = '/governance/standard-association/has-columns';
const associationPath = '/asset/standard-association/page';

describe('标准关联信息查询', () => {
  beforeEach(() => vi.resetAllMocks());

  it.each(['element', 'codeset'] as const)(
    '%s 无有效关联字段时直接返回空分页，不依赖资产服务',
    async (kind) => {
      vi.mocked(requestClient.get).mockImplementation(async (url) => {
        if (url === availabilityPath) return false;
        throw new Error('503 Unable to find instance for asset-server');
      });

      await expect(
        getStandardAssociations(kind, { id: '1', pageNo: 1, pageSize: 10 }),
      ).resolves.toEqual({ list: [], total: 0 });

      expect(requestClient.get).toHaveBeenCalledExactlyOnceWith(
        availabilityPath,
        { params: { id: '1', kind } },
      );
    },
  );

  it('有落标字段时查询资产关联并保留分页、标识与时间原始值', async () => {
    const params = { id: '1', pageNo: 2, pageSize: 20 };
    const result = {
      list: [
        {
          id: '9007199254740993',
          assetName: '关联资产',
          tableName: 'test_table',
          columnNames: ['test_column'],
          createTime: 1_700_000_000_000,
        },
      ],
      total: 21,
    };
    vi.mocked(requestClient.get)
      .mockResolvedValueOnce(true)
      .mockResolvedValueOnce(result);

    await expect(getStandardAssociations('element', params)).resolves.toBe(
      result,
    );

    expect(requestClient.get).toHaveBeenNthCalledWith(1, availabilityPath, {
      params: { id: params.id, kind: 'element' },
    });
    expect(requestClient.get).toHaveBeenNthCalledWith(2, associationPath, {
      params: { ...params, kind: 'element' },
    });
  });

  it('已落标但没有可见关联资产时仍正常返回空分页', async () => {
    vi.mocked(requestClient.get)
      .mockResolvedValueOnce(true)
      .mockResolvedValueOnce({ list: [], total: 0 });

    await expect(
      getStandardAssociations('codeset', { id: 1, pageNo: 1, pageSize: 10 }),
    ).resolves.toEqual({ list: [], total: 0 });
    expect(requestClient.get).toHaveBeenCalledTimes(2);
  });

  it('目录鉴权或落标查询失败时保留异常，不能冒充无关联', async () => {
    const error = new Error('无权查询标准');
    vi.mocked(requestClient.get).mockRejectedValueOnce(error);

    await expect(
      getStandardAssociations('element', { id: 1, pageNo: 1, pageSize: 10 }),
    ).rejects.toBe(error);
    expect(requestClient.get).toHaveBeenCalledTimes(1);
  });

  it('有落标字段但资产服务不可用时保留异常', async () => {
    const error = new Error('资产服务不可用');
    vi.mocked(requestClient.get)
      .mockResolvedValueOnce(true)
      .mockRejectedValueOnce(error);

    await expect(
      getStandardAssociations('element', { id: 1, pageNo: 1, pageSize: 10 }),
    ).rejects.toBe(error);
    expect(requestClient.get).toHaveBeenCalledTimes(2);
  });
});
