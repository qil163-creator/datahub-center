import type { PageParam, PageResult } from '@vben/request';

import type { FoundationDatabaseApi } from './index';

import { requestClient } from '#/api/request';

export namespace DatabaseMonitorApi {
  export type Id = FoundationDatabaseApi.Id;
  export type Metric = 'CONNECTION_STATUS' | 'RESPONSE_TIME';
  export type Operator = 'EQ' | 'GT' | 'GTE' | 'LT' | 'LTE';
  export type Level = 'GENERAL' | 'SERIOUS' | 'URGENT';
  export type AlarmStatus = 'HANDLED' | 'IGNORED' | 'PENDING';
  export type LogStatus =
    | 'FAILED'
    | 'RUNNING'
    | 'STOPPED'
    | 'SUCCESS'
    | 'UNKNOWN'
    | 'WAITING';

  export interface Overview {
    collectionStatus?: 'FAILED' | 'SUCCESS' | 'UNSUPPORTED' | null;
    collectionMessage?: null | string;
    connectionStatus: 0 | 1 | 2;
    lastCollectedTime: null | number;
    responseTimeMs: null | number;
    todayOutputRows: string;
    todayInputRows: string;
    totalTransferRows: string;
    pendingAlarmCount: number;
    averageResponseTimeMs: null | number;
    readSpeed: null | number;
    writeSpeed: null | number;
    throughputUnit: 'rows/s';
    capacityUsed: null | string;
    capacityTotal: null;
    supportedMetrics: string[];
    logCoverage: string;
  }

  export interface Trend {
    days: number;
    points: {
      date: string;
      inputRows: string;
      onlineRate: null | number;
      outputRows: string;
      readSpeed: null | number;
      responseTimeMs: null | number;
      writeSpeed: null | number;
    }[];
    supportedMetrics: string[];
  }

  export interface PageReq extends PageParam {
    databaseId: Id;
  }

  export interface Log {
    id: string;
    recordId: Id;
    taskName: string;
    description: string;
    sourceModule: 'ACCESS' | 'DEV';
    outputRows: null | string;
    inputRows: null | string;
    status: LogStatus;
    createTime: number;
    startTime: null | number;
    endTime: null | number;
    durationMs: null | number;
  }

  export interface RuleSave {
    id?: Id;
    databaseId: Id;
    name: string;
    description?: string;
    metric: Metric;
    operator: Operator;
    threshold: string;
    level: Level;
    status: 0 | 1;
    owner: string;
  }

  export interface Rule extends RuleSave {
    id: Id;
    creator: string;
    createTime: number;
    updateTime: number;
  }

  export interface Alarm {
    id: Id;
    databaseId: Id;
    ruleId: Id;
    ruleName: string;
    description: string;
    metric: Metric;
    operator: Operator;
    threshold: string;
    currentValue: string;
    level: Level;
    status: AlarmStatus;
    firstTime: number;
    lastTime: number;
    occurrences: number;
    handledBy?: string;
    handledTime?: number;
    handleRemark?: string;
  }

  export interface AlarmHandle {
    databaseId: Id;
    id: Id;
    action: 'HANDLE' | 'IGNORE';
    remark: string;
  }
}

const base = '/dataforge/database-monitor';

export function getDatabaseMonitorOverview(databaseId: DatabaseMonitorApi.Id) {
  return requestClient.get<DatabaseMonitorApi.Overview>(`${base}/overview`, {
    params: { databaseId },
  });
}

export function getDatabaseMonitorTrend(
  databaseId: DatabaseMonitorApi.Id,
  days: number,
) {
  return requestClient.get<DatabaseMonitorApi.Trend>(`${base}/trend`, {
    params: { databaseId, days },
  });
}

export function collectDatabaseMonitor(databaseId: DatabaseMonitorApi.Id) {
  return requestClient.post<DatabaseMonitorApi.Overview>(
    `${base}/collect`,
    undefined,
    { params: { databaseId } },
  );
}

export function getDatabaseMonitorLogPage(params: DatabaseMonitorApi.PageReq) {
  return requestClient.get<PageResult<DatabaseMonitorApi.Log>>(
    `${base}/log/page`,
    {
      params,
    },
  );
}

export function getDatabaseAlarmPage(params: DatabaseMonitorApi.PageReq) {
  return requestClient.get<PageResult<DatabaseMonitorApi.Alarm>>(
    `${base}/alarm/page`,
    {
      params,
    },
  );
}

export function getDatabaseAlarm(
  databaseId: DatabaseMonitorApi.Id,
  id: DatabaseMonitorApi.Id,
) {
  return requestClient.get<DatabaseMonitorApi.Alarm>(`${base}/alarm/get`, {
    params: { databaseId, id },
  });
}

export function handleDatabaseAlarm(data: DatabaseMonitorApi.AlarmHandle) {
  return requestClient.put<boolean>(`${base}/alarm/handle`, data);
}

export function getDatabaseRulePage(params: DatabaseMonitorApi.PageReq) {
  return requestClient.get<PageResult<DatabaseMonitorApi.Rule>>(
    `${base}/rule/page`,
    {
      params,
    },
  );
}

export function getDatabaseRule(
  databaseId: DatabaseMonitorApi.Id,
  id: DatabaseMonitorApi.Id,
) {
  return requestClient.get<DatabaseMonitorApi.Rule>(`${base}/rule/get`, {
    params: { databaseId, id },
  });
}

export function createDatabaseRule(data: DatabaseMonitorApi.RuleSave) {
  return requestClient.post<DatabaseMonitorApi.Id>(`${base}/rule/create`, data);
}

export function updateDatabaseRule(data: DatabaseMonitorApi.RuleSave) {
  return requestClient.put<boolean>(`${base}/rule/update`, data);
}

export function updateDatabaseRuleStatus(data: {
  databaseId: DatabaseMonitorApi.Id;
  id: DatabaseMonitorApi.Id;
  status: 0 | 1;
}) {
  return requestClient.put<boolean>(`${base}/rule/status`, data);
}

export function deleteDatabaseRule(
  databaseId: DatabaseMonitorApi.Id,
  id: DatabaseMonitorApi.Id,
) {
  return requestClient.delete<boolean>(`${base}/rule/delete`, {
    params: { databaseId, id },
  });
}
