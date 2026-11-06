import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMasterDataApi {
  export type Id = number | string;
  export type Row = Record<string, unknown>;
  /** 主数据目录节点 */
  export interface Catalog {
    id?: number;
    parentId?: number;
    type?: number; // 0-普通目录 1-主数据
    name?: string;
    orderNo?: number;
    description?: string;
    path?: string;
    orgId?: number;
    modelStatus?: null | number; // 主数据节点：null-未建模 0-未发布 1-已发布
    createTime?: string;
    children?: Catalog[];
  }

  /** 模型字段 */
  export interface Column {
    id?: Id;
    name?: string;
    commentStr?: string;
    fieldType?: number;
    fieldLength?: null | number;
    fieldScale?: null | number;
    nullable?: number; // 0-否 1-是
    pk?: number; // 0-否 1-是
    sort?: number;
    standardElementId?: Id;
  }

  /** 主数据模型 */
  export interface Model {
    id?: Id;
    catalogId?: Id;
    domainId?: Id;
    domainName?: string;
    databaseId?: Id;
    databaseName?: string;
    databaseType?: string;
    databaseHost?: string;
    lastMaintenanceTime?: number;
    tableName?: string;
    tableCn?: string;
    description?: string;
    status?: number; // 0-草稿 1-已发布 2-已下线
    version?: number;
    approvalEnabled?: boolean;
    approvalProcessKey?: string;
    enabledCodeField?: string;
    maintenanceSupported?: boolean;
    maintenanceUnsupportedReason?: string;
    /** 与字段类型选项取交集，避免展示数据源无法执行的操作。 */
    queryFilterOperators?: DataFilterOperator[];
    publishState?: 'APPLYING' | 'READY' | 'UNKNOWN' | null;
    publishMessage?: string;
    releaseTime?: number;
    releaseUserName?: string;
    orgId?: Id;
    createTime?: number;
    creator?: string;
    creatorName?: string;
    fieldCount?: Id;
    columns?: Column[];
  }

  /** 按需读取物理表数据量，未知结果不能显示为零。 */
  export interface ModelDataCount {
    available: boolean;
    dataCount?: Id | null;
    message?: string;
  }

  /** 保存平台模型定义；发布状态与版本由服务端发布流程控制。 */
  export interface ModelSaveReq {
    id?: Id;
    /** 修改时核对读取详情的版本，避免覆盖已发布的新版本。 */
    expectedVersion?: number;
    domainId: Id;
    databaseId: Id;
    tableName: string;
    tableCn: string;
    description?: string;
    approvalEnabled?: boolean;
    approvalProcessKey?: string;
    columns: Column[];
  }

  /** 主数据记录分页结果 */
  export interface DataPageResult {
    columns?: string[];
    list?: Record<string, any>[];
    total?: number;
  }

  export type DataFilterOperator =
    | 'CONTAINS'
    | 'EQ'
    | 'GE'
    | 'GT'
    | 'IS_EMPTY'
    | 'IS_NOT_EMPTY'
    | 'IS_NOT_NULL'
    | 'IS_NULL'
    | 'LE'
    | 'LT'
    | 'NE'
    | 'NOT_CONTAINS';

  export interface DataCondition {
    name: string;
    /** 省略时兼容原查询规则；新筛选条件显式指定操作符。 */
    operator?: DataFilterOperator;
    /** NULL 与空字符串操作不需要传值；其他操作全程保持文本精度。 */
    value?: string;
  }

  export interface ModelPageReq extends PageParam {
    domainId?: Id;
    name?: string;
    tableName?: string;
    status?: number;
    createTime?: [string, string];
  }

  export interface Statistics {
    draft: number;
    offline: number;
    released: number;
    total: number;
  }
}

/** ==================== 目录 ==================== */

export function getMasterCatalogList() {
  return requestClient.get<GovernanceMasterDataApi.Catalog[]>(
    '/governance/master-catalog/list',
  );
}

export function createMasterCatalog(data: GovernanceMasterDataApi.Catalog) {
  return requestClient.post('/governance/master-catalog/create', data);
}

export function updateMasterCatalog(data: GovernanceMasterDataApi.Catalog) {
  return requestClient.put('/governance/master-catalog/update', data);
}

export function deleteMasterCatalog(id: number) {
  return requestClient.delete(`/governance/master-catalog/delete?id=${id}`);
}

/** ==================== 模型 ==================== */

export function getMasterModelByCatalog(catalogId: number) {
  return requestClient.get<GovernanceMasterDataApi.Model | null>(
    `/governance/master-model/get-by-catalog?catalogId=${catalogId}`,
  );
}

export function createMasterModel(data: GovernanceMasterDataApi.ModelSaveReq) {
  return requestClient.post<GovernanceMasterDataApi.Id>(
    '/governance/master-model/create',
    data,
  );
}

export function updateMasterModel(data: GovernanceMasterDataApi.ModelSaveReq) {
  return requestClient.put('/governance/master-model/update', data);
}

export function deleteMasterModel(id: GovernanceMasterDataApi.Id) {
  return requestClient.delete(`/governance/master-model/delete?id=${id}`);
}

export function releaseMasterModel(id: GovernanceMasterDataApi.Id) {
  return requestClient.put(`/governance/master-model/release?id=${id}`);
}

export function cancelReleaseMasterModel(id: GovernanceMasterDataApi.Id) {
  return requestClient.put(`/governance/master-model/cancel-release?id=${id}`);
}

export function verifyMasterModelRelease(id: GovernanceMasterDataApi.Id) {
  return requestClient.put<boolean>(
    '/governance/master-model/verify-release',
    undefined,
    { params: { id } },
  );
}

export function getMasterModel(id: GovernanceMasterDataApi.Id) {
  return requestClient.get<GovernanceMasterDataApi.Model>(
    '/governance/master-model/get',
    { params: { id } },
  );
}

export function getMasterModelPage(
  params: GovernanceMasterDataApi.ModelPageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterDataApi.Model>>(
    '/governance/master-model/page',
    { params },
  );
}

export function getMasterModelStatistics(
  domainId?: GovernanceMasterDataApi.Id,
) {
  return requestClient.get<GovernanceMasterDataApi.Statistics>(
    '/governance/master-model/statistics',
    { params: { domainId } },
  );
}

export function getMasterModelDataCount(id: GovernanceMasterDataApi.Id) {
  return requestClient.get<GovernanceMasterDataApi.ModelDataCount>(
    '/governance/master-model/data-count',
    { params: { id }, silentError: true },
  );
}

export function getMasterModelSimpleList() {
  return requestClient.get<GovernanceMasterDataApi.Model[]>(
    '/governance/master-model/simple-list',
  );
}

export function deleteMasterModelList(ids: GovernanceMasterDataApi.Id[]) {
  return requestClient.delete<boolean>('/governance/master-model/delete-list', {
    params: { ids: ids.join(',') },
  });
}

/** 已发布模型精简列表（派发任务选择用） */
export function getReleasedMasterModelList() {
  return requestClient.get<GovernanceMasterDataApi.Model[]>(
    '/governance/master-model/released-list',
  );
}

/** 从数据源已有表提取字段定义（建模拾取字段用） */
export function getMasterTableColumns(
  databaseId: GovernanceMasterDataApi.Id,
  tableName: string,
) {
  return requestClient.get<GovernanceMasterDataApi.Column[]>(
    '/governance/master-model/table-columns',
    { params: { databaseId, tableName } },
  );
}

/** ==================== 数据维护 ==================== */

export function getMaintainModelPage(
  params: GovernanceMasterDataApi.ModelPageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterDataApi.Model>>(
    '/governance/master-data/model-page',
    { params },
  );
}

export function getMaintainModel(id: GovernanceMasterDataApi.Id) {
  return requestClient.get<GovernanceMasterDataApi.Model>(
    '/governance/master-data/model',
    { params: { id } },
  );
}

export function getMaintainModelDataCount(id: GovernanceMasterDataApi.Id) {
  return requestClient.get<GovernanceMasterDataApi.ModelDataCount>(
    '/governance/master-data/model-data-count',
    { params: { id } },
  );
}

export function getMasterDataPage(data: {
  conditions?: GovernanceMasterDataApi.DataCondition[];
  modelId: GovernanceMasterDataApi.Id;
  pageNo: number;
  pageSize: number;
}) {
  return requestClient.post<GovernanceMasterDataApi.DataPageResult>(
    '/governance/master-data/page',
    data,
  );
}

export function addMasterData(
  modelId: GovernanceMasterDataApi.Id,
  rows: GovernanceMasterDataApi.Row[],
) {
  return requestClient.post('/governance/master-data/add', { modelId, rows });
}

export function updateMasterData(
  modelId: GovernanceMasterDataApi.Id,
  rows: GovernanceMasterDataApi.Row[],
) {
  return requestClient.post('/governance/master-data/update', {
    modelId,
    rows,
  });
}

export function deleteMasterData(
  modelId: GovernanceMasterDataApi.Id,
  rows: GovernanceMasterDataApi.Row[],
) {
  return requestClient.post('/governance/master-data/delete', {
    modelId,
    rows,
  });
}
