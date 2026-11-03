import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

import { getAuditRuleDimensionList } from '../auditrule';

export namespace QualityCatalogApi {
  export type Kind = 'audit' | 'clean';
  export type Id = number | string;
  export interface Rule {
    id: Id;
    name?: string;
    description?: string;
    dimensionId?: Id;
    dimensionName?: string;
    categoryId?: Id;
    categoryName?: string;
    useCase?: string;
    example?: string;
    creator?: string;
    creatorName?: string;
    createTime?: number | string;
  }
  export interface Category {
    id: Id;
    parentId?: Id;
    categoryName?: string;
    categoryCode?: string;
    children?: Category[];
  }
  export interface PageReq extends PageParam {
    name?: string;
    description?: string;
    categoryId?: Id;
    dimensionId?: Id;
    sortField: 'createTime' | 'id';
    sortOrder: 'asc' | 'desc';
  }
}

const base = (kind: QualityCatalogApi.Kind) => `/governance/${kind}-rule`;

export function getRuleCatalogPage(
  kind: QualityCatalogApi.Kind,
  params: QualityCatalogApi.PageReq,
) {
  return requestClient.get<PageResult<QualityCatalogApi.Rule>>(
    `${base(kind)}/page`,
    { params },
  );
}

export async function getRuleCatalogCategories(kind: QualityCatalogApi.Kind) {
  if (kind === 'audit') {
    const dimensions = await getAuditRuleDimensionList();
    return dimensions
      .toSorted((left, right) => left.sort - right.sort)
      .map((dimension) => ({
        id: dimension.id,
        categoryName: dimension.name,
        categoryCode: dimension.code,
      }));
  }
  return requestClient.get<QualityCatalogApi.Category[]>(
    `${base(kind)}/category-list`,
  );
}

export function getRuleCatalogDetail(
  kind: QualityCatalogApi.Kind,
  id: QualityCatalogApi.Id,
) {
  return requestClient.get<null | QualityCatalogApi.Rule>(`${base(kind)}/get`, {
    params: { id },
  });
}
