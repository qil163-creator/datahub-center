import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getMyApplication,
  getMyApplicationsPage,
  getMyAsset,
  getMyAssetApiDocument,
  getMyAssetsPage,
  previewMyAsset,
} from './index';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());

describe('我的资产消费契约', () => {
  it('申请记录只发送允许的筛选条件，详情保留无损申请编号', async () => {
    const id = '9223372036854775807';
    const params = { pageNo: 1, pageSize: 10, keyword: '标题', status: 0, ifSelf: 0 };
    await getMyApplicationsPage(params);
    await getMyApplication(id);
    expect(requestClient.get).toHaveBeenNthCalledWith(1, '/asset/market/my-assets/applications/page', {
      params: { pageNo: 1, pageSize: 10, keyword: '标题', status: 0 },
    });
    expect(requestClient.get).toHaveBeenNthCalledWith(2, '/asset/market/my-assets/applications/get', { params: { id } });
  });
  it('分页只发送白名单筛选，不允许客户端附加用户范围', async () => {
    const params = {
      pageNo: 2,
      pageSize: 10,
      keyword: '门诊',
      deliveryType: 1,
      marketCategoryId: '9223372036854775807',
      userId: '99',
      ifSelf: 0,
    };
    await getMyAssetsPage(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/my-assets/page',
      {
        params: {
          pageNo: 2,
          pageSize: 10,
          keyword: '门诊',
          deliveryType: 1,
          marketCategoryId: '9223372036854775807',
        },
      },
    );
  });
  it('详情和预览均使用无损申请编号，预览保持消费键', async () => {
    const id = '9223372036854775807';
    vi.mocked(requestClient.get).mockResolvedValue({ rows: [{ code: '01' }] });
    await getMyAsset(id);
    const value = await previewMyAsset(id, 20, 'read-stable-key');
    expect(value).toEqual({ rows: [{ code: '01' }] });
    expect(requestClient.get).toHaveBeenNthCalledWith(
      1,
      '/asset/market/my-assets/get',
      { params: { id } },
    );
    expect(requestClient.get).toHaveBeenNthCalledWith(
      2,
      '/asset/market/my-assets/preview',
      {
        params: { id, limit: 20 },
        headers: { 'X-Consumption-Key': 'read-stable-key' },
      },
    );
  });
  it('文档只读取本人批准范围的消费者接口', async () => {
    await getMyAssetApiDocument('9223372036854775807');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/my-assets/api-document',
      { params: { id: '9223372036854775807' } },
    );
  });
});
