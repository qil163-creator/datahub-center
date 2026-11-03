import type { PageParam, PageResult } from '@vben/request';

import type { GovernanceStandardCodeSetApi } from './index';

import { requestClient } from '#/api/request';
type Id = GovernanceStandardCodeSetApi.Id;
export interface StandardCodeMapping {
  id: Id;
  codeSetId: Id;
  originalValue: string;
  codeItemId: Id;
  revision: Id;
  codeName?: string;
  codeValue?: string;
  creatorName?: string;
  createTime?: number | string;
  updateTime?: number | string;
}
export interface MappingSave {
  id?: Id;
  codeSetId: Id;
  originalValue: string;
  codeItemId: Id;
  revision?: Id;
}
const base = '/governance/standard-code-set';
export function getCodeMappingPage(
  params: PageParam & {
    codeSetId: Id;
    originalValue?: string;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  },
) {
  return requestClient.get<PageResult<StandardCodeMapping>>(
    `${base}/mapping-page`,
    { params },
  );
}
export function getCodeMapping(codeSetId: Id, id: Id) {
  return requestClient.get<StandardCodeMapping>(`${base}/mapping-get`, {
    params: { codeSetId, id },
  });
}
export function createCodeMapping(data: MappingSave) {
  return requestClient.post<Id>(`${base}/mapping-create`, data);
}
export function updateCodeMapping(data: MappingSave) {
  return requestClient.put<boolean>(`${base}/mapping-update`, data);
}
export function deleteCodeMapping(codeSetId: Id, id: Id) {
  return requestClient.delete<boolean>(`${base}/mapping-delete`, {
    params: { codeSetId, id },
  });
}
