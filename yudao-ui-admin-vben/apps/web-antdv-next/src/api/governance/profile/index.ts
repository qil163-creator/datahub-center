import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 数据探查：Long 编号按无损类型保留；比率、平均值为后端输出的十进制字符串；时间为毫秒时间戳。
 * 权限：发起 governance:profile:run，查询 governance:profile:query。
 */
export namespace GovernanceProfileApi {
  export type Id = number | string;
  export type RunStatus = 'FAILED' | 'PENDING' | 'RUNNING' | 'SUCCESS';
  export type ColumnKind = 'BOOLEAN' | 'NUMERIC' | 'OTHER' | 'TEMPORAL' | 'TEXT';
  export type RuleType = 'ENUM' | 'LENGTH' | 'NOT_NULL' | 'UNIQUE';

  /** 探查记录。 */
  export interface Run {
    id: Id;
    metaTableId: Id;
    schemaName?: string;
    tableName?: string;
    status: RunStatus;
    sampleLimit?: number;
    sampleRows?: number;
    /** 全表总行数；统计失败时为空 */
    totalRows?: number;
    columnCount?: number;
    executorUserId?: Id;
    executorName?: string;
    startTime?: number;
    endTime?: number;
    failReason?: string;
    attemptCount?: number;
    createTime?: number;
  }

  /** Top 值。 */
  export interface TopValue {
    rankNo: number;
    /** 已脱敏时为空 */
    value?: null | string;
    count: number;
    /** 占非空数比例，十进制字符串 0~1 */
    ratio?: string;
    masked: boolean;
  }

  /** 字段指标（基于抽样）。 */
  export interface Column {
    id: Id;
    metaColumnId?: Id;
    columnName: string;
    dataType?: string;
    columnKind: ColumnKind;
    ordinalPosition?: number;
    nonNullCount: number;
    nullCount: number;
    /** 十进制字符串 0~1 */
    nullRate: string;
    distinctCount?: number;
    /** 十进制字符串 0~1 */
    uniqueRate?: string;
    minValue?: null | string;
    maxValue?: null | string;
    valueMasked: boolean;
    /** 脱敏原因：RULE/UNCLASSIFIED/UNTRACEABLE/UNVERIFIED/METADATA/MASK_FAILED */
    maskReason?: null | string;
    avgLength?: string;
    minLength?: number;
    maxLength?: number;
    avgValue?: string;
    topValues: TopValue[];
  }

  /** 规则建议：只展示，不自动创建规则。 */
  export interface RuleSuggestion {
    columnName: string;
    ruleType: RuleType;
    strategyKey: string;
    qualityDim: string;
    ruleName: string;
    reason: string;
    params: Record<string, unknown>;
  }

  export interface Latest {
    latestRun?: null | Run;
    resultRun?: null | Run;
    columns: Column[];
    suggestions: RuleSuggestion[];
  }

  export interface RunPageReq extends PageParam {
    metaTableId: Id;
    status?: RunStatus;
  }
}

/** 发起数据探查，返回探查记录编号。 */
export function createProfileRun(metaTableId: GovernanceProfileApi.Id) {
  return requestClient.post<GovernanceProfileApi.Id>('/governance/profile/run', {
    metaTableId,
  });
}

/** 最新探查结果与规则建议。 */
export function getLatestProfile(
  metaTableId: GovernanceProfileApi.Id,
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceProfileApi.Latest>(
    '/governance/profile/latest',
    { params: { metaTableId }, signal },
  );
}

/** 探查记录分页。 */
export function getProfileRunPage(
  params: GovernanceProfileApi.RunPageReq,
  signal?: AbortSignal,
) {
  return requestClient.get<PageResult<GovernanceProfileApi.Run>>(
    '/governance/profile/run/page',
    { params, signal },
  );
}
