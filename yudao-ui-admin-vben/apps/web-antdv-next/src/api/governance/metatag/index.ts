import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMetaTagApi {
  export type Id = number | string;

  export interface Tag {
    id?: Id;
    name?: string;
    category?: string;
    color?: string;
    refCount?: number;
    remark?: string;
    createTime?: number;
  }

  export interface BindReq {
    objectType: number;
    objectId: Id;
    revision: number;
    tagIds: Id[];
  }

  export interface BindingSnapshot {
    objectType: number;
    objectId: Id;
    revision: number;
    tagIds: Id[];
    tags: Tag[];
  }
}

export function getMetaTagPage(params: PageParam) {
  return requestClient.get<PageResult<GovernanceMetaTagApi.Tag>>(
    '/governance/meta-tag/page',
    { params },
  );
}

export function getMetaTag(id: GovernanceMetaTagApi.Id) {
  return requestClient.get<GovernanceMetaTagApi.Tag>(
    `/governance/meta-tag/get?id=${id}`,
  );
}

export function getMetaTagSimpleList() {
  return requestClient.get<GovernanceMetaTagApi.Tag[]>(
    '/governance/meta-tag/simple-list',
  );
}

export function createMetaTag(data: GovernanceMetaTagApi.Tag) {
  return requestClient.post<GovernanceMetaTagApi.Id>(
    '/governance/meta-tag/create',
    data,
  );
}

export function updateMetaTag(data: GovernanceMetaTagApi.Tag) {
  return requestClient.put('/governance/meta-tag/update', data);
}

export function deleteMetaTag(id: GovernanceMetaTagApi.Id) {
  return requestClient.delete(`/governance/meta-tag/delete?id=${id}`);
}

export function bindMetaTags(data: GovernanceMetaTagApi.BindReq) {
  return requestClient.put('/governance/meta-tag/bind', data);
}

export function getMetaTagsByObject(
  objectType: number,
  objectId: GovernanceMetaTagApi.Id,
) {
  return requestClient.get<GovernanceMetaTagApi.Tag[]>(
    '/governance/meta-tag/list-by-object',
    { params: { objectType, objectId } },
  );
}

export function getMetaTagBindingSnapshot(
  objectType: number,
  objectId: GovernanceMetaTagApi.Id,
) {
  return requestClient.get<GovernanceMetaTagApi.BindingSnapshot>(
    '/governance/meta-tag/binding-snapshot',
    { params: { objectType, objectId } },
  );
}
