import type { DataAssetApi } from './index';

import { requestClient } from '#/api/request';

export interface AssetEtlBatchDescribe {
  batchId: DataAssetApi.Id;
  recordId: DataAssetApi.Id;
  nodeNo: string;
  databaseId: DataAssetApi.Id;
  schemaName: string;
  tableName: string;
  payloadHash: string;
  runStatus: number;
  commitStatus: string;
  qualityStatus: string;
  qualityInstanceId?: DataAssetApi.Id;
  inputCount?: DataAssetApi.Id;
  outputCount?: DataAssetApi.Id;
  passCount?: DataAssetApi.Id;
  warnCount?: DataAssetApi.Id;
  rejectCount?: DataAssetApi.Id;
  available: boolean;
  fields: string[];
}

export interface AssetEtlBatchBinding {
  id: DataAssetApi.Id;
  assetId: DataAssetApi.Id;
  revision: number;
  batchId: DataAssetApi.Id;
  recordId: DataAssetApi.Id;
  payloadHash: string;
  databaseId: DataAssetApi.Id;
  schemaName: string;
  tableName: string;
  actorId: DataAssetApi.Id;
  reason: string;
  batch: AssetEtlBatchDescribe;
}

export interface AssetEtlBatchRows {
  rows: Record<string, unknown>[];
  nextAfterRow: DataAssetApi.Id;
  hasMore: boolean;
}

/** 历史关联快照，不表示对应批次当前可消费。 */
export interface AssetEtlBatchBindingHistory extends Omit<
  AssetEtlBatchBinding,
  'batch'
> {
  createTime: number;
}

export function getAssetEtlBatchHistory(params: {
  assetId: DataAssetApi.Id;
  beforeRevision?: number;
  limit?: number;
}) {
  return requestClient.get<{
    hasMore: boolean;
    list: AssetEtlBatchBindingHistory[];
    nextBeforeRevision?: number;
  }>('/asset/data-asset/etl-batch/history', { params });
}

export function getAssetEtlBatchBinding(assetId: DataAssetApi.Id) {
  return requestClient.get<AssetEtlBatchBinding | null>(
    '/asset/data-asset/etl-batch/get',
    { params: { assetId } },
  );
}

export function bindAssetEtlBatch(data: {
  assetId: DataAssetApi.Id;
  batchId: DataAssetApi.Id;
  expectedRevision?: number;
  reason: string;
}) {
  return requestClient.post<number>('/asset/data-asset/etl-batch/bind', data);
}

export function getAssetEtlBatchRows(
  assetId: DataAssetApi.Id,
  expectedRevision: number,
  afterRow: DataAssetApi.Id = '0',
  limit = 100,
) {
  return requestClient.get<AssetEtlBatchRows>(
    '/asset/data-asset/etl-batch/rows',
    { params: { assetId, expectedRevision, afterRow, limit } },
  );
}
