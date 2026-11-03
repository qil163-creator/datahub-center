import { requestClient } from '#/api/request';

export namespace GovernanceCategoryTermApi {
  export interface Category {
    id: number | string;
    parentId?: number | string;
    categoryName?: string;
    categoryCode?: string;
    sort?: number;
    status?: number;
    remark?: string;
    creator?: string;
    createTime?: number | string;
  }
  export interface Save {
    id?: number | string;
    parentId: number | string;
    categoryName: string;
    categoryCode?: string;
    sort: number;
    status: number;
    remark?: string;
  }
}

const base = '/governance/category-term';

export function getCategoryTermList(params?: Record<string, unknown>) {
  return requestClient.get<GovernanceCategoryTermApi.Category[]>(`${base}/list`, { params });
}

export function getCategoryTerm(id: number | string) {
  return requestClient.get<GovernanceCategoryTermApi.Category>(`${base}/get`, { params: { id } });
}

export function createCategoryTerm(data: GovernanceCategoryTermApi.Save) {
  return requestClient.post<number | string>(`${base}/create`, data);
}

export function updateCategoryTerm(data: GovernanceCategoryTermApi.Save) {
  return requestClient.put<boolean>(`${base}/update`, data);
}

export function deleteCategoryTerm(id: number | string) {
  return requestClient.delete<boolean>(`${base}/delete`, { params: { id } });
}
