import type { PageParam, PageResult } from '@vben/request';

import type { DataAssetApi } from '../dataasset';

import { requestClient } from '#/api/request';

export namespace AssetTagApi {
  export interface Tag {
    id?: number | string;
    revision?: number;
    sourceRecordId?: string;
    sourceCreatorName?: string;
    sourceUpdaterName?: string;
    name?: string;
    category?: string;
    categoryId?: number | string;
    categoryName?: string;
    color?: string;
    nearSynonyms?: string;
    synonyms?: string;
    description?: string;
    status?: number;
    remark?: string;
    assetCount?: number;
    creator?: string;
    creatorName?: string;
    updater?: string;
    updaterName?: string;
    createTime?: number | string;
    updateTime?: number | string;
  }
  export interface LinkedAsset {
    relationId: DataAssetApi.Id;
    relationRevision: number;
    sourceRecordId?: string;
    creator?: string;
    assetId: DataAssetApi.Id;
    assetName?: string;
    tableName?: string;
    description?: string;
    assetType?: number;
    categoryName?: string;
    subjectDomainName?: string;
    creatorName?: string;
    createTime?: number | string;
  }
}

export function getAssetTagPage(
  params: PageParam & {
    categoryId?: number | string;
    creatorId?: number;
    creatorName?: string;
    name?: string;
    sortField?: 'assetCount' | 'createTime' | 'id' | 'name';
    sortOrder?: 'asc' | 'desc';
    status?: number;
  },
) {
  return requestClient.get<PageResult<AssetTagApi.Tag>>('/asset/tag/page', {
    params,
  });
}

export function getAssetTag(id: number | string) {
  return requestClient.get<AssetTagApi.Tag>(`/asset/tag/get?id=${id}`);
}

export function getAssetTagList() {
  return requestClient.get<AssetTagApi.Tag[]>('/asset/tag/list');
}

export function createAssetTag(data: AssetTagApi.Tag) {
  return requestClient.post<number | string>('/asset/tag/create', data);
}

export function updateAssetTag(data: AssetTagApi.Tag) {
  return requestClient.put('/asset/tag/update', data);
}

export function updateAssetTagStatus(
  id: number | string,
  status: number,
  revision: number,
) {
  return requestClient.put(
    `/asset/tag/update-status?id=${id}&status=${status}&revision=${revision}`,
  );
}

export function deleteAssetTag(id: number | string, revision: number) {
  return requestClient.delete(
    `/asset/tag/delete?id=${id}&revision=${revision}`,
  );
}

export function getTagsByAssetId(assetId: number | string) {
  return requestClient.get<AssetTagApi.Tag[]>('/asset/tag/asset-list', {
    params: { assetId },
  });
}

export function bindAssetTags(
  assetId: number | string,
  tagIds: (number | string)[],
  catalogRevision: DataAssetApi.Id,
) {
  return requestClient.put('/asset/tag/bind', {
    assetId,
    tagIds,
    catalogRevision,
  });
}

export function deleteAssetTagList(
  items: { id: DataAssetApi.Id; revision: number }[],
) {
  return requestClient.post<boolean>('/asset/tag/delete-list', { items });
}
export function getAssetTagLinkedAssets(
  params: PageParam & {
    id: DataAssetApi.Id;
    keyword?: string;
    sortField?: 'assetId' | 'createTime';
    sortOrder?: 'asc' | 'desc';
  },
) {
  return requestClient.get<PageResult<AssetTagApi.LinkedAsset>>(
    '/asset/tag/linked-assets',
    { params },
  );
}
export function unbindAssetTag(relationId: DataAssetApi.Id, revision: number) {
  return requestClient.delete<boolean>('/asset/tag/unbind', {
    params: { relationId, revision },
  });
}
