import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SupplyDataApiApi {
  export type Id = number | string;
  /** 请求参数定义项 */
  export interface ParamItem {
    name?: string; // 参数名称
    nullable?: number; // 是否允许为空：0-否 1-是
    description?: string;
    paramType?: string; // STRING/INTEGER/DOUBLE/DATE
    exampleValue?: string;
    defaultValue?: string;
  }

  /** 返回字段定义项 */
  export interface FieldItem {
    name?: string; // 列名
    outputName?: string; // 输出名称（参数名映射）
    cnName?: string; // 中文名称
    description?: string;
    dataType?: string;
    timeFormat?: string;
    exampleValue?: string;
    model?: string; // 数据模型：HIDE/CONCAT/REPLACE/SUBSTRING/SPLIT/DATE_FORMAT
    modelRule?: string; // 模型规则
    desensitize?: number; // 是否脱敏：0-否 1-是
  }

  export interface DataApiSimple {
    categoryId?: Id;
    id?: Id;
    method?: string;
    name?: string;
    path?: string;
    status?: number;
    version?: string;
    unpublishedDraft?: boolean;
    activeReleaseId?: Id;
    providerDeptId?: Id;
  }

  /** API 服务信息 */
  export interface DataApi {
    id?: Id;
    categoryId?: Id;
    name?: string;
    version?: string;
    path?: string;
    method?: string; // GET/POST
    resultFormat?: string; // PAGE/LIST/DETAIL
    description?: string;
    ipWhitelist?: string;
    ipRuleType?: string; // WHITE-白名单 BLACK-黑名单
    limitEnabled?: number;
    limitSeconds?: number;
    limitCount?: number;
    status?: number; // 0-下线 1-上线
    apiType?: string; // QUERY/INSERT/UPDATE/DELETE/MULTI
    authType?: string; // NONE/APPKEY/SIGN
    cacheEnabled?: number;
    cacheSeconds?: number;
    breakerEnabled?: number;
    breakerErrorCount?: number;
    breakerWindowSeconds?: number;
    breakerOpenSeconds?: number;
    alarmEnabled?: number;
    configType?: string; // TABLE/SQL/FORWARD/ETL_BATCH
    etlBatchId?: Id;
    etlBatchHash?: string;
    schemaName?: string;
    databaseId?: Id;
    tableName?: string;
    sqlText?: string;
    forwardUrl?: string;
    forwardMethod?: string;
    requestParams?: ParamItem[];
    responseFields?: FieldItem[];
    remark?: string;
    creator?: string;
    createTime?: string;
    updateTime?: string;
    providerDeptId?: Id;
    resourceRef?: string;
    ownerUserId?: Id;
    activeReleaseId?: Id;
    draftRevision?: number;
    unpublishedDraft?: boolean;
    contractHash?: string;
    rowVersion?: number;
  }

  export interface DataApiRelease {
    active?: boolean;
    apiId?: Id;
    apiVersion?: string;
    configHash?: string;
    contractHash?: string;
    id?: Id;
    publishTime?: number | string;
    publisher?: string;
    revision?: number;
  }

  /** 数据表列元数据 */
  export interface TableColumn {
    name?: string;
    dataType?: string;
    length?: number;
    scale?: number;
    pk?: number;
    nullable?: number;
    defaultValue?: string;
    comment?: string;
  }

  /** 执行结果 */
  export interface ExecuteResult {
    nextAfterRow?: Id;
    hasMore?: boolean;
    rows?: Record<string, any>[];
    total?: number;
    raw?: string;
    durationMs?: number;
    testRunNo?: string;
    configHash?: string;
    executeMode?: string;
    operationId?: Id;
    operationKey?: string;
    operationStatus?: string;
    consumptionKey?: string;
    consumptionState?: 'CANCELLED' | 'COMMITTED' | 'EXPIRED' | 'RESERVED';
    consumptionRows?: number;
    operationMessage?: string;
    replayed?: boolean;
  }

  /** 数据源精简信息 */
  export interface DatabaseSimple {
    id?: Id;
    name?: string;
    productType?: string;
    iconPath?: string;
    databaseType?: Id | null;
    databaseTypeName?: null | string;
    categoryCode?: null | string;
  }
}

/** 查询 API 服务分页 */
export function getDataApiPage(params: PageParam) {
  return requestClient.get<PageResult<SupplyDataApiApi.DataApi>>(
    '/supply/data-api/page',
    { params },
  );
}

/** 查询 API 服务精简列表 */
export function getDataApiSimpleList(
  status?: number,
  purpose?: 'grant' | 'query' | 'test',
) {
  return requestClient.get<SupplyDataApiApi.DataApiSimple[]>(
    '/supply/data-api/simple-list',
    { params: { purpose, status } },
  );
}

/** 查询 API 服务详情 */
export function getDataApi(id: SupplyDataApiApi.Id) {
  return requestClient.get<SupplyDataApiApi.DataApi>('/supply/data-api/get', {
    params: { id },
  });
}

/** 新增 API 服务 */
export function createDataApi(data: SupplyDataApiApi.DataApi) {
  return requestClient.post<number>('/supply/data-api/create', data);
}

/** 修改 API 服务 */
export function updateDataApi(data: SupplyDataApiApi.DataApi) {
  return requestClient.put('/supply/data-api/update', data);
}

/** 上线/下线 API 服务 */
export function updateDataApiStatus(
  id: SupplyDataApiApi.Id,
  status: number,
  expectedRowVersion: number,
) {
  return requestClient.put('/supply/data-api/update-status', {
    id,
    status,
    expectedRowVersion,
  });
}

/** 删除 API 服务 */
export function deleteDataApi(id: SupplyDataApiApi.Id) {
  return requestClient.delete('/supply/data-api/delete', { params: { id } });
}

export function getDataApiPublicBaseUrl() {
  return requestClient.get<string>('/supply/data-api/public-base-url');
}

/** 数据源精简列表（向导下拉用） */
export function getDataApiDatabaseList() {
  return requestClient.get<SupplyDataApiApi.DatabaseSimple[]>(
    '/supply/data-api/database-list',
  );
}

/** 数据源下的表名列表 */
export function getDataApiTableList(databaseId: SupplyDataApiApi.Id) {
  return requestClient.get<string[]>('/supply/data-api/table-list', {
    params: { databaseId },
  });
}

/** 数据表列元数据 */
export function getDataApiTableColumns(
  databaseId: SupplyDataApiApi.Id,
  tableName: string,
) {
  return requestClient.get<SupplyDataApiApi.TableColumn[]>(
    '/supply/data-api/table-columns',
    { params: { databaseId, tableName } },
  );
}

/** 向导测试执行（配置可未保存） */
export function testDataApi(data: {
  api: SupplyDataApiApi.DataApi;
  pageNo?: number;
  pageSize?: number;
  params?: Record<string, any>;
}) {
  return requestClient.post<SupplyDataApiApi.ExecuteResult>(
    '/supply/data-api/test',
    data,
  );
}

export function publishDataApi(
  id: SupplyDataApiApi.Id,
  expectedRowVersion: number,
) {
  return requestClient.post<SupplyDataApiApi.Id>('/supply/data-api/publish', {
    expectedRowVersion,
    id,
  });
}

export function rollbackDataApi(
  id: SupplyDataApiApi.Id,
  releaseId: SupplyDataApiApi.Id,
  expectedRowVersion: number,
) {
  return requestClient.post('/supply/data-api/rollback', {
    expectedRowVersion,
    id,
    releaseId,
  });
}

export function getDataApiReleaseList(apiId: SupplyDataApiApi.Id) {
  return requestClient.get<SupplyDataApiApi.DataApiRelease[]>(
    '/supply/data-api/release/list',
    { params: { apiId } },
  );
}

export function signDataApi(data: {
  apiPath: string;
  appKey: string;
  appSecret: string;
  body?: string;
  contentType?: string;
  keyVersion?: string;
  method: string;
  nonce: string;
  queryString?: string;
  signVersion?: string;
  timestamp: string;
}) {
  return requestClient.post<string>('/supply/data-api/sign', data);
}
