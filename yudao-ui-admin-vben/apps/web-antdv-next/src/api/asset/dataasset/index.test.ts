import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getDataAssetColumn,
  getDataAssetNavigationCategories,
  getDataAssetPage,
  updateDataAssetColumn,
} from './index';
vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('asset navigation category transport', () => {
  it('无损传递板块筛选编号', async () => {
    await getDataAssetPage({ pageNo: 1, pageSize: 10, dataDomainId: '9223372036854775807' });
    expect(requestClient.get).toHaveBeenLastCalledWith('/asset/data-asset/page', {
      params: { pageNo: 1, pageSize: 10, dataDomainId: '9223372036854775807' },
    });
  });
  it('uses the query-authorized navigation endpoint and keeps long category ids intact', async () => {
    const rows = [
      { id: '9223372036854775807', parentId: 0, categoryName: '生产类目' },
    ];
    vi.mocked(requestClient.get).mockResolvedValue(rows);
    expect(await getDataAssetNavigationCategories()).toEqual(rows);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/data-asset/navigation-categories',
    );
  });
  it('使用资产维护入口读取当前字段，CAS提交不转换大ID', async () => {
    await getDataAssetColumn('9007199254740993', '9007199254740994');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/data-asset/column-get',
      { params: { assetId: '9007199254740993', id: '9007199254740994' } },
    );
    await updateDataAssetColumn({
      assetId: '9007199254740993',
      id: '9007199254740994',
      revision: 9,
      standardElementId: 12,
      safetyLevelId: '9223372036854775807',
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/asset/data-asset/update-column',
      expect.objectContaining({
        assetId: '9007199254740993',
        id: '9007199254740994',
        revision: 9,
        safetyLevelId: '9223372036854775807',
      }),
    );
  });
});
