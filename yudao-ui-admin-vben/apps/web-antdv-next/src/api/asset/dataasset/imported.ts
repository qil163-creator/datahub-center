import type { PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace ImportedAssetApi {
  export type Id = number | string;
  export type Values = Record<string, null | string>;

  export interface Snapshot {
    sourceAssetId: string;
    sourceDataCount: number;
    importedRowCount: number;
    complete: boolean;
    sourceName?: string;
    sourceType?: string;
    fileInfo?: Record<string, unknown>;
    quality?: {
      evaluations?: Evaluation[];
      statistics?: Statistics;
      task?: QualityTask;
    };
    lineage?: Record<string, unknown>;
  }

  export interface QualityTask {
    sourceId?: Id;
    taskName?: string;
    status?: string;
    cronExpression?: string;
    priority?: string;
    workerGroup?: string;
    retryCount?: number;
    retryInterval?: number;
    delayExecTime?: number;
    executeStrategy?: string;
    description?: string;
    createTime?: number | string;
    updateTime?: number | string;
  }

  export interface Evaluation {
    sourceId?: Id;
    name?: string;
    ruleCode?: string;
    ruleName?: string;
    warningLevel?: string;
    dimensionType?: string;
    strategyKey?: string;
    status?: string;
    description?: string;
    errorDescription?: string;
    repairAdvice?: string;
    whereClause?: string;
    columnName?: string;
    config?: Record<string, unknown>;
  }

  export interface Statistics {
    sourceLogId?: string;
    trend?: { title?: string[]; value?: (null | string)[] };
    dimensions?: {
      dimensionType?: string;
      problemTotal?: string;
      proportion?: null | string;
      succesTotal?: string;
      total?: string;
      trendType?: string;
      yoyIncrease?: null | string;
    }[];
    items?: {
      checkDate?: number | string;
      columnName?: string;
      config?: Record<string, unknown>;
      dimensionType?: string;
      problemTotal?: string;
      proportion?: null | string;
      ruleCode?: string;
      ruleDescription?: string;
      ruleName?: string;
      sourceEvaluateId?: Id;
      sourceId?: Id;
      sourceTaskLogId?: Id;
      tableName?: string;
      total?: string;
    }[];
  }

  export interface RowChange {
    columnName: string;
    columnLabel?: string;
    oldValue: null | string;
    newValue: null | string;
  }

  export interface RowLog {
    id: Id;
    rowId: string;
    revision: number;
    actorName?: string;
    createTime?: number | string;
    changes: RowChange[];
  }
}

export function updateImportedAssetRow(data: {
  id: ImportedAssetApi.Id;
  revision: number;
  rowId: string;
  values: ImportedAssetApi.Values;
}) {
  return requestClient.post<boolean>(
    '/asset/data-asset/import-row/update',
    data,
  );
}

export function getImportedAssetRowLogPage(params: {
  createTime?: [number, number];
  creatorName?: string;
  id: ImportedAssetApi.Id;
  pageNo: number;
  pageSize: number;
  rowId?: string;
}) {
  return requestClient.get<PageResult<ImportedAssetApi.RowLog>>(
    '/asset/data-asset/import-row/log-page',
    { params },
  );
}
