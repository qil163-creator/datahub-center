import { beforeEach, expect, it, vi } from 'vitest';

import {
  getDepartmentAsset,
  getDepartmentAssetApiDocument,
  getDepartmentAssetCategories,
  getDepartmentAssetPreview,
  getDepartmentAssetsPage,
} from './department-assets';

const mock = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('#/api/request', () => ({ requestClient: mock }));
beforeEach(() => vi.clearAllMocks());
it('queries department applications without an actor or department supplied by the client', async () => {
  const query = {
    pageNo: 1,
    pageSize: 10,
    keyword: '河流',
    deliveryType: 2,
    marketCategoryId: '9007199254740994',
    sortField: 'applyId' as const,
    sortOrder: 'desc' as const,
  };
  await getDepartmentAssetsPage(query);
  expect(mock.get).toHaveBeenCalledWith('/asset/market/department-assets/page', { params: query });
});
it('preserves a large native application ID for exact scoped detail selection', async () => {
  await getDepartmentAsset('9007199254740993');
  expect(mock.get).toHaveBeenCalledWith('/asset/market/department-assets/get', {
    params: { id: '9007199254740993' },
  });
});
it('reads categories through the same department-scoped feature', async () => {
  await getDepartmentAssetCategories();
  expect(mock.get).toHaveBeenCalledWith('/asset/market/department-assets/categories');
});
it('keeps lazy preview and document reads bound to the native application ID', async () => {
  await getDepartmentAssetPreview('9007199254740993');
  await getDepartmentAssetApiDocument('9007199254740993');
  expect(mock.get).toHaveBeenCalledWith('/asset/market/department-assets/preview', {
    params: { id: '9007199254740993', limit: 20 },
  });
  expect(mock.get).toHaveBeenCalledWith('/asset/market/department-assets/api-document', {
    params: { id: '9007199254740993' },
  });
});
