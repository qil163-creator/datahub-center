import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace ModelingDataDomainApi {
  export interface NamingRule {
    tableType: 'ADS' | 'DIM' | 'DWS' | 'FACT_EVENT' | 'FACT_PROCESS' | 'ODS';
    prefix: string;
    daySuffix: string;
    hourSuffix: string;
    minuteSuffix: string;
    includeSubjectAbbreviation: boolean;
  }
  export interface PageReq extends PageParam {
    name?: string;
    leaderUserId?: number | string;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }
  /** 数据分域信息 */
  export interface DataDomain {
    id?: number | string;
    name?: string; // 数据分域名称
    code?: string; // 英文缩写
    leaderUserId?: number | string; // 负责人用户编号
    leaderPhone?: string; // 负责人电话
    status?: number; // 状态：0-启用 1-停用
    description?: string; // 描述
    remark?: string; // 备注
    creator?: string; // 创建者编号
    createTime?: number | string;
    updater?: string; // 更新者编号
    updateTime?: number | string;
    icon?: string;
    businessOwnerIds?: (number | string)[];
    dataOwnerIds?: (number | string)[];
    namingRules?: NamingRule[];
    leaderName?: string;
    creatorName?: string;
    creatorPhone?: string;
    subjectCount?: null | number;
    businessObjectCount?: null | number;
    businessActivityCount?: null | number;
  }
  export interface SaveReq {
    id?: number | string;
    name: string;
    code: string;
    leaderUserId: number | string;
    leaderPhone?: string;
    status?: number;
    description?: string;
    remark?: string;
    icon?: string;
    businessOwnerIds?: (number | string)[];
    dataOwnerIds?: (number | string)[];
    namingRules?: NamingRule[];
  }
}

/** 查询数据分域分页 */
export function getDataDomainPage(params: ModelingDataDomainApi.PageReq) {
  return requestClient.get<PageResult<ModelingDataDomainApi.DataDomain>>(
    '/modeling/data-domain/page',
    { params },
  );
}

/** 查询数据分域全量列表（下拉选择用） */
export function getDataDomainSimpleList() {
  return requestClient.get<ModelingDataDomainApi.DataDomain[]>(
    '/modeling/data-domain/simple-list',
  );
}

/** 查询数据分域详情 */
export function getDataDomain(id: number | string) {
  return requestClient.get<ModelingDataDomainApi.DataDomain>(
    `/modeling/data-domain/get?id=${id}`,
  );
}

/** 新增数据分域 */
export function createDataDomain(data: ModelingDataDomainApi.SaveReq) {
  return requestClient.post('/modeling/data-domain/create', data);
}

/** 修改数据分域 */
export function updateDataDomain(data: ModelingDataDomainApi.SaveReq) {
  return requestClient.put('/modeling/data-domain/update', data);
}

export function updateDataDomainNamingRules(
  id: number | string,
  namingRules: ModelingDataDomainApi.NamingRule[],
) {
  return requestClient.put<boolean>(
    '/modeling/data-domain/update-naming-rules',
    { id, namingRules },
  );
}

/** 修改数据分域状态 */
export function updateDataDomainStatus(id: number | string, status: number) {
  return requestClient.put('/modeling/data-domain/update-status', {
    id,
    status,
  });
}

/** 删除数据分域 */
export function deleteDataDomain(id: number | string) {
  return requestClient.delete(`/modeling/data-domain/delete?id=${id}`);
}

/** 批量删除数据板块 */
export function deleteDataDomainList(ids: (number | string)[]) {
  return requestClient.delete('/modeling/data-domain/delete-list', {
    params: { ids: ids.join(',') },
  });
}
