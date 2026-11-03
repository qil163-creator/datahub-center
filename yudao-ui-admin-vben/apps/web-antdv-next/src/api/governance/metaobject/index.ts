import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceMetaObjectApi {
  export type Id = number | string;
  export interface TablePageReq extends PageParam {
    tableName?: string;
    comment?: string;
    dbId?: Id;
    datasourceId?: Id;
    sourceSystemId?: Id;
    publishStatus?: number;
  }
  /** 元数据树节点：来源系统 → 数据源 → 库 */
  export interface TreeNode {
    key?: string;
    type?: string; // system/datasource/db
    id?: Id;
    name?: string;
    dbType?: string;
    tableCount?: number;
    objectStatus?: number; // 1-正常 2-待确认删除
    children?: TreeNode[];
  }

  /** 表元数据 */
  export interface MetaTable {
    id?: Id;
    resourceUrn?: string;
    origin?: number; // 1-采集 2-手工 3-导入
    dbId?: Id;
    dbName?: string;
    datasourceId?: Id;
    datasourceName?: string;
    sourceSystemId?: Id;
    host?: string;
    port?: number;
    username?: string;
    dbType?: string;
    sourceSystemName?: string;
    tableName?: string;
    tableType?: string; // TABLE/VIEW
    sourceComment?: string;
    customComment?: string;
    businessName?: string;
    layer?: string;
    columnCount?: number;
    primaryKeys?: string;
    indexes?: string;
    partitionInfo?: string;
    engine?: string;
    storageType?: string;
    structVersion?: string;
    rowCount?: number;
    storageSize?: number;
    dataUpdateTime?: number;
    tbCreateTime?: number;
    structChangeCount?: number;
    lastStructChangeTime?: number;
    masterFlag?: number;
    temporaryFlag?: number;
    sensitiveFlag?: number;
    businessDefinition?: string;
    subjectDomainId?: Id;
    subjectDomainName?: string;
    catalogLinked?: number;
    techLeaderUserId?: Id;
    techLeaderName?: string;
    techLeaderPhone?: string;
    businessLeaderUserId?: Id;
    businessLeaderName?: string;
    businessLeaderPhone?: string;
    ownerDeptId?: Id;
    objectStatus?: number; // 1-正常 2-待确认删除 3-已删除
    publishStatus?: number; // 0-未发布 1-已发布
    publishedVersion?: string;
    publishedSnapshotId?: Id;
    publishTime?: number;
    stateRevision?: number;
    finalized?: boolean;
    finalizedVersion?: string;
    finalizedSnapshotId?: Id;
    finalizedTime?: number;
    finalizedBy?: string;
    readOnly?: boolean;
    creatorName?: string;
    creatorPhone?: string;
    creator?: string;
    updater?: string;
    updaterName?: string;
    updaterPhone?: string;
    updateTime?: number;
    healthScore?: number;
    firstSeenTime?: number;
    lastSeenTime?: number;
    remark?: string;
    createTime?: number;
    bizRevision?: number;
  }

  /** 字段元数据 */
  export interface MetaColumn {
    id?: Id;
    resourceUrn?: string;
    tableId?: Id;
    columnName?: string;
    ordinalPosition?: number;
    sourceComment?: string;
    customComment?: string;
    businessName?: string;
    dataType?: string;
    dataLength?: number;
    dataPrecision?: number;
    dataScale?: number;
    nullableFlag?: number;
    defaultValue?: string;
    pkFlag?: number;
    fkFlag?: number;
    uniqueFlag?: number;
    autoIncrementFlag?: number;
    businessDefinition?: string;
    measuringUnit?: string;
    standardElementId?: number;
    standardMappingId?: Id;
    standardElementCode?: string;
    standardElementName?: string;
    safetyLevelId?: Id;
    sensitiveFlag?: number;
    revision?: number;
    creatorName?: string;
    creator?: string;
    creatorPhone?: string;
    updater?: string;
    updaterName?: string;
    updaterPhone?: string;
    createTime?: number;
    updateTime?: number;
    objectStatus?: number;
    lastSeenTime?: number;
    remark?: string;
  }

  /** 表结构快照 */
  export interface Snapshot {
    id?: Id;
    tableId?: Id;
    structVersion?: string;
    instanceId?: Id;
    changeSource?: number; // 1-采集 2-手工 3-导入 4-回滚
    snapshot?: string;
    snapshotHash?: string;
    changeSummary?: string;
    baselineFlag?: number;
    creatorName?: string;
    createTime?: number;
  }

  export interface FinalizedDetail {
    table: MetaTable;
    columns: MetaColumn[];
    snapshot: Snapshot;
  }

  export interface PropertyChange {
    property: string;
    before?: unknown;
    after?: unknown;
  }

  export interface SnapshotComparison {
    tableChanges: PropertyChange[];
    columnChanges: {
      after?: SnapshotColumn;
      before?: SnapshotColumn;
      changes: PropertyChange[];
      changeType: 'ADDED' | 'MODIFIED' | 'REMOVED' | 'UNCHANGED';
      columnName: string;
    }[];
    addedCount: number;
    removedCount: number;
    modifiedCount: number;
  }

  export interface SnapshotColumn {
    columnName?: string;
    comment?: string;
    dataType?: string;
    nullable?: number;
    defaultValue?: string;
  }

  /** 变更明细 */
  export interface Change {
    id?: Id;
    instanceId?: Id;
    changeSource?: number;
    objectType?: number; // 1-库 2-表 3-字段
    objectId?: Id;
    tableId?: Id;
    dbId?: Id;
    changeType?: number; // 1-新增 2-修改 3-删除
    property?: string;
    beforeValue?: string;
    afterValue?: string;
    versionFrom?: string;
    versionTo?: string;
    operator?: string;
    createTime?: number;
  }

  /** 表业务属性维护 */
  export interface TableBizUpdate {
    id: Id;
    bizRevision: number;
    customComment?: string;
    businessName?: string;
    businessDefinition?: string;
    storageType?: string;
    layer?: string;
    masterFlag?: number;
    temporaryFlag?: number;
    sensitiveFlag?: number;
    subjectDomainId?: Id;
    subjectDomainName?: string;
    techLeaderUserId?: Id;
    techLeaderPhone?: string;
    businessLeaderUserId?: Id;
    businessLeaderPhone?: string;
    ownerDeptId?: Id;
    remark?: string;
  }

  /** 库级批量发布结果 */
  export interface PublishBatchResult {
    total?: number;
    published?: number;
    alreadyPublished?: number;
    skipped?: number;
    skippedReasons?: string[];
  }

  /** 字段业务属性维护 */
  export interface ColumnBizUpdate {
    id: Id;
    revision: number;
    customComment?: string;
    businessName?: string;
    businessDefinition?: string;
    measuringUnit?: string;
    standardElementId?: number;
    safetyLevelId?: Id;
    sensitiveFlag?: number;
    remark?: string;
  }
}

/** 元数据树 */
export function getMetaObjectTree() {
  return requestClient.get<GovernanceMetaObjectApi.TreeNode[]>(
    '/governance/meta-object/tree',
  );
}

/** 确认删除库 */
export function confirmDeleteMetaDb(id: GovernanceMetaObjectApi.Id) {
  return requestClient.put(
    `/governance/meta-object/confirm-delete-db?id=${id}`,
  );
}

/** 表元数据分页 */
export function getMetaTablePage(params: GovernanceMetaObjectApi.TablePageReq) {
  return requestClient.get<PageResult<GovernanceMetaObjectApi.MetaTable>>(
    '/governance/meta-table/page',
    { params },
  );
}

/** 表元数据详情 */
export function getMetaTableDetail(id: number | string) {
  return requestClient.get<GovernanceMetaObjectApi.MetaTable>(
    `/governance/meta-table/get?id=${id}`,
  );
}

/** 维护表业务属性 */
export function updateMetaTableBiz(
  data: GovernanceMetaObjectApi.TableBizUpdate,
) {
  return requestClient.put('/governance/meta-table/update-biz', data);
}

/** 确认删除表 */
export function confirmDeleteMetaTable(id: GovernanceMetaObjectApi.Id) {
  return requestClient.put(`/governance/meta-table/confirm-delete?id=${id}`);
}

export function deleteMetaTable(data: {
  id: GovernanceMetaObjectApi.Id;
  stateRevision: number;
}) {
  return requestClient.put<boolean>('/governance/meta-table/delete', data);
}

/** 发布状态与定版版本独立维护。 */
export function publishMetaTable(
  id: GovernanceMetaObjectApi.Id,
  stateRevision: number,
) {
  return requestClient.put<boolean>(
    '/governance/meta-table/publish',
    undefined,
    { params: { id, stateRevision } },
  );
}

/** 取消发布 */
export function unpublishMetaTable(
  id: GovernanceMetaObjectApi.Id,
  stateRevision: number,
) {
  return requestClient.put<boolean>(
    '/governance/meta-table/unpublish',
    undefined,
    { params: { id, stateRevision } },
  );
}

export function finalizeMetaTable(data: {
  id: GovernanceMetaObjectApi.Id;
  snapshotId: GovernanceMetaObjectApi.Id;
  stateRevision: number;
}) {
  return requestClient.put<boolean>('/governance/meta-table/finalize', data);
}

export function cancelFinalizeMetaTable(data: {
  id: GovernanceMetaObjectApi.Id;
  stateRevision: number;
}) {
  return requestClient.put<boolean>(
    '/governance/meta-table/cancel-finalize',
    data,
  );
}

export function getFinalizedMetaTablePage(
  params: GovernanceMetaObjectApi.TablePageReq,
) {
  return requestClient.get<PageResult<GovernanceMetaObjectApi.MetaTable>>(
    '/governance/meta-table/finalized-page',
    { params },
  );
}

export function getFinalizedMetaTable(id: GovernanceMetaObjectApi.Id) {
  return requestClient.get<GovernanceMetaObjectApi.FinalizedDetail>(
    '/governance/meta-table/finalized-get',
    { params: { id } },
  );
}

export function compareMetaSnapshots(params: {
  fromSnapshotId: GovernanceMetaObjectApi.Id;
  tableId: GovernanceMetaObjectApi.Id;
  toSnapshotId: GovernanceMetaObjectApi.Id;
}) {
  return requestClient.get<GovernanceMetaObjectApi.SnapshotComparison>(
    '/governance/meta-table/compare-snapshots',
    { params },
  );
}

/** 库级批量发布 */
export function publishMetaDb(id: GovernanceMetaObjectApi.Id) {
  return requestClient.put<GovernanceMetaObjectApi.PublishBatchResult>(
    `/governance/meta-object/publish-db?id=${id}`,
  );
}

/** 字段列表 */
export function getMetaColumnList(tableId: number | string) {
  return requestClient.get<GovernanceMetaObjectApi.MetaColumn[]>(
    `/governance/meta-table/column-list?tableId=${tableId}`,
  );
}

/** 字段编辑前读取当前业务属性及关联标准。 */
export function getMetaColumnDetail(id: GovernanceMetaObjectApi.Id) {
  return requestClient.get<GovernanceMetaObjectApi.MetaColumn>(
    '/governance/meta-table/column-get',
    { params: { id } },
  );
}

/** 维护字段业务属性 */
export function updateMetaColumnBiz(
  data: GovernanceMetaObjectApi.ColumnBizUpdate,
) {
  return requestClient.put('/governance/meta-table/update-column-biz', data);
}

/** 版本快照列表（不含快照体） */
export function getMetaSnapshotList(tableId: number | string) {
  return requestClient.get<GovernanceMetaObjectApi.Snapshot[]>(
    `/governance/meta-table/snapshot-list?tableId=${tableId}`,
  );
}

/** 快照详情（含全结构快照体） */
export function getMetaSnapshot(id: GovernanceMetaObjectApi.Id) {
  return requestClient.get<GovernanceMetaObjectApi.Snapshot>(
    `/governance/meta-table/get-snapshot?id=${id}`,
  );
}

/** 变更明细分页 */
export function getMetaChangePage(params: PageParam) {
  return requestClient.get<PageResult<GovernanceMetaObjectApi.Change>>(
    '/governance/meta-table/change-page',
    { params },
  );
}

/** 检索命中 */
export interface MetaSearchHit {
  objectType?: number;
  objectId?: number;
  tableId?: number;
  dbName?: string;
  tableName?: string;
  columnName?: string;
  businessName?: string;
  comment?: string;
  hitField?: string;
  structVersion?: string;
  publishStatus?: number;
  sourceSystemName?: string;
}

/** 概览 */
export interface MetaOverview {
  dbCount?: number;
  tableCount?: number;
  columnCount?: number;
  publishedTableCount?: number;
  unpublishedTableCount?: number;
  pendingDeleteTableCount?: number;
  datasourceCount?: number;
  sourceSystemCount?: number;
  commentCoverage?: number;
  ownerCoverage?: number;
  collectSuccessRate?: number;
  collectInstanceCount?: number;
  changeTrend?: {
    addCount?: number;
    day?: string;
    deleteCount?: number;
    modifyCount?: number;
  }[];
  recentFails?: {
    errorType?: string;
    instanceId?: number;
    stage?: string;
    startTime?: string;
    taskId?: number;
    taskName?: string;
  }[];
}

/** 元数据检索 */
export function searchMetaObject(params: PageParam) {
  return requestClient.get<PageResult<MetaSearchHit>>(
    '/governance/meta-object/search',
    { params },
  );
}

/** 批量认责 */
export function batchAssignMetaOwner(data: {
  businessLeaderPhone?: string;
  businessLeaderUserId?: GovernanceMetaObjectApi.Id;
  ids: GovernanceMetaObjectApi.Id[];
  ownerDeptId?: GovernanceMetaObjectApi.Id;
  techLeaderPhone?: string;
  techLeaderUserId?: GovernanceMetaObjectApi.Id;
}) {
  return requestClient.put('/governance/meta-table/batch-owner', data);
}
