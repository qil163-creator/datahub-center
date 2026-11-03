import { beforeEach, expect, it, vi } from 'vitest';

import {
  bindAssetTags,
  deleteAssetTag,
  deleteAssetTagList,
  getAssetTagLinkedAssets,
  getAssetTagPage,
  unbindAssetTag,
  updateAssetTagStatus,
} from './index';
const mock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: mock }));
beforeEach(() => vi.clearAllMocks());
it('preserves free-text creator and global sorting parameters', () => {
  getAssetTagPage({
    pageNo: 2,
    pageSize: 10,
    categoryId: '9007199254740993',
    creatorName: '创建人甲',
    sortField: 'assetCount',
    sortOrder: 'desc',
  });
  expect(mock.get).toHaveBeenCalledWith('/asset/tag/page', {
    params: expect.objectContaining({
      categoryId: '9007199254740993',
      creatorName: '创建人甲',
      sortField: 'assetCount',
    }),
  });
});
it('sends all bulk delete revisions to one atomic endpoint', () => {
  const items = [
    { id: '9007199254740993', revision: 7 },
    { id: '9007199254740994', revision: 8 },
  ];
  deleteAssetTagList(items);
  expect(mock.post).toHaveBeenCalledWith('/asset/tag/delete-list', { items });
});
it('sends revision CAS for status and single delete', () => {
  updateAssetTagStatus('9007199254740993', 1, 7);
  deleteAssetTag('9007199254740994', 8);
  expect(mock.put).toHaveBeenCalledWith(
    '/asset/tag/update-status?id=9007199254740993&status=1&revision=7',
  );
  expect(mock.delete).toHaveBeenCalledWith(
    '/asset/tag/delete?id=9007199254740994&revision=8',
  );
});
it('uses relation IDs and revisions for exact unbinding and requests server pagination', () => {
  getAssetTagLinkedAssets({
    id: '9007199254740993',
    pageNo: 1,
    pageSize: 10,
    sortField: 'createTime',
    sortOrder: 'desc',
  });
  unbindAssetTag('9007199254740994', 9);
  expect(mock.get).toHaveBeenCalledWith('/asset/tag/linked-assets', {
    params: expect.objectContaining({
      id: '9007199254740993',
      sortField: 'createTime',
    }),
  });
  expect(mock.delete).toHaveBeenCalledWith('/asset/tag/unbind', {
    params: { relationId: '9007199254740994', revision: 9 },
  });
});
it('keeps the asset catalog revision lossless during full tag replacement', () => {
  bindAssetTags('9007199254740993', ['9007199254740994'], '9007199254740995');
  expect(mock.put).toHaveBeenCalledWith('/asset/tag/bind', {
    assetId: '9007199254740993',
    tagIds: ['9007199254740994'],
    catalogRevision: '9007199254740995',
  });
});
