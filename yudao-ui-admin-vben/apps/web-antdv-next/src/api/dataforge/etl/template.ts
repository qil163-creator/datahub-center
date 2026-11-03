import type { PageResult } from '@vben/request';

import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlTemplateGraph {
  nodes: Record<string, any>[];
  edges: Record<string, any>[];
}
export interface EtlTemplate {
  id: DataforgeEtlApi.Id;
  name: string;
  description?: string;
  ownerUserId: DataforgeEtlApi.Id;
  nodeCount: number;
  canDelete: boolean;
  createTime: number;
}
export interface EtlTemplateDetail extends EtlTemplate, EtlTemplateGraph {}
export function getEtlTemplatePage(params: {
  name?: string;
  pageNo: number;
  pageSize: number;
}) {
  return requestClient.get<PageResult<EtlTemplate>>(
    '/dataforge/etl-template/page',
    { params },
  );
}
export function getEtlTemplate(id: DataforgeEtlApi.Id) {
  return requestClient.get<EtlTemplateDetail>('/dataforge/etl-template/get', {
    params: { id },
  });
}
export function createEtlTemplate(
  data: EtlTemplateGraph & {
    description?: string;
    name: string;
    requestId: string;
  },
) {
  return requestClient.post<DataforgeEtlApi.Id>(
    '/dataforge/etl-template/create',
    data,
  );
}
export function deleteEtlTemplate(id: DataforgeEtlApi.Id) {
  return requestClient.delete<boolean>('/dataforge/etl-template/delete', {
    params: { id },
  });
}
