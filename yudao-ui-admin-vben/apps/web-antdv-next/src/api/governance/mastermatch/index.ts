import type { PageParam, PageResult } from '@vben/request';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { requestClient } from '#/api/request';

export namespace GovernanceMasterMatchApi {
  export type Id = number | string;
  export type Row = Record<string, unknown>;
  export type Algorithm = 'EDIT' | 'EXACT' | 'NUMERIC' | 'TEXT';
  export type Survivorship =
    | 'MAX'
    | 'MIN'
    | 'MOST_COMPLETE'
    | 'MOST_FREQUENT'
    | 'SURVIVOR';
  export type GroupStatus =
    | 'IGNORED'
    | 'MERGED'
    | 'MERGING'
    | 'PENDING'
    | 'STALE';

  export interface RuleField {
    name: string;
    algorithm: Algorithm;
    weight: number;
  }

  /** 相似匹配规则；阈值为十进制字符串 */
  export interface Rule {
    modelId: Id;
    fields: RuleField[];
    blockingFields: string[];
    threshold: string;
    survivorship: Record<string, Survivorship>;
    maxRows: number;
    configured?: boolean;
    updateTime?: number;
  }

  export interface Run {
    id: Id;
    modelId: Id;
    status: 'FAILED' | 'RUNNING' | 'SUCCESS';
    scannedRows?: number;
    comparedPairs?: Id;
    groupCount?: number;
    message?: string;
    startedAt?: number;
    finishedAt?: number;
    runByName?: string;
  }

  export interface Member {
    key: string;
    row: Row;
    /** 仅详情：false 表示记录已不存在，row 为匹配时快照 */
    exists?: boolean;
  }

  export interface Pair {
    left: number;
    right: number;
    score: string;
    fields: Record<string, string>;
  }

  export interface Suggestion {
    survivor: number;
    values: Row;
    sources: Record<string, number>;
  }

  export interface Group {
    id: Id;
    runId: Id;
    modelId: Id;
    status: GroupStatus;
    memberCount: number;
    maxScore: string;
    members: Member[];
    pairs?: Pair[];
    suggestion?: Suggestion;
    changeId?: Id;
    changeStatus?: string;
    handledByName?: string;
    handledAt?: number;
    handleRemark?: string;
    createTime?: number;
  }

  export interface GroupPageReq extends PageParam {
    modelId: Id;
    status?: GroupStatus;
    runId?: Id;
  }

  export interface MergeReq {
    groupId: Id;
    requestId: string;
    survivorKey: string;
    mergedKeys: string[];
    values: Row;
  }

  export interface MutationResult {
    id: Id;
    status: string;
    approvalRequired?: boolean;
    message?: string;
  }

  export interface GoldenSource {
    sourceKey: string;
    sourceRow: Row;
    changeId: Id;
    groupId?: Id;
    mergedAt?: number;
    mergedByName?: string;
  }

  export interface Golden {
    goldenKey: string;
    mergedCount: Id;
    lastMergedAt?: number;
    sources: GoldenSource[];
  }
}

const BASE = '/governance/master-match';

export function getMatchModelPage(
  params: GovernanceMasterDataApi.ModelPageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterDataApi.Model>>(
    `${BASE}/model-page`,
    { params },
  );
}

export function getMatchModel(id: GovernanceMasterMatchApi.Id) {
  return requestClient.get<GovernanceMasterDataApi.Model>(`${BASE}/model`, {
    params: { id },
  });
}

export function getMatchRule(modelId: GovernanceMasterMatchApi.Id) {
  return requestClient.get<GovernanceMasterMatchApi.Rule>(`${BASE}/rule`, {
    params: { modelId },
  });
}

export function saveMatchRule(data: GovernanceMasterMatchApi.Rule) {
  return requestClient.put<GovernanceMasterMatchApi.Rule>(`${BASE}/rule`, data);
}

export function runMatch(modelId: GovernanceMasterMatchApi.Id) {
  return requestClient.post<GovernanceMasterMatchApi.Run>(
    `${BASE}/run`,
    undefined,
    { params: { modelId } },
  );
}

export function getMatchRunPage(
  params: PageParam & { modelId: GovernanceMasterMatchApi.Id },
) {
  return requestClient.get<PageResult<GovernanceMasterMatchApi.Run>>(
    `${BASE}/run-page`,
    { params },
  );
}

export function getMatchGroupPage(
  params: GovernanceMasterMatchApi.GroupPageReq,
) {
  return requestClient.get<PageResult<GovernanceMasterMatchApi.Group>>(
    `${BASE}/group-page`,
    { params },
  );
}

export function getMatchGroup(id: GovernanceMasterMatchApi.Id) {
  return requestClient.get<GovernanceMasterMatchApi.Group>(`${BASE}/group`, {
    params: { id },
  });
}

export function mergeMatchGroup(data: GovernanceMasterMatchApi.MergeReq) {
  return requestClient.post<GovernanceMasterMatchApi.MutationResult>(
    `${BASE}/merge`,
    data,
  );
}

export function ignoreMatchGroup(
  groupId: GovernanceMasterMatchApi.Id,
  remark?: string,
) {
  return requestClient.post<boolean>(`${BASE}/ignore`, { groupId, remark });
}

export function getGoldenPage(
  params: PageParam & { modelId: GovernanceMasterMatchApi.Id },
) {
  return requestClient.get<PageResult<GovernanceMasterMatchApi.Golden>>(
    `${BASE}/golden-page`,
    { params },
  );
}
