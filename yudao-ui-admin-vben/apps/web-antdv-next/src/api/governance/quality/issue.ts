import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityIssueDataApi {
  export type Id = number | string;
  export interface SourceRow {
    rowKey: string;
    values: Record<string, null | string>;
    state: 'IGNORED' | 'UNPROCESSED';
    revision: number;
    remark: string;
    processable: boolean;
  }
  export interface RowsRequest {
    auditLogId: Id;
    pageNo: number;
    pageSize: number;
    filters?: Record<string, string>;
  }
  export interface RowsResult {
    issueId?: Id;
    columns: string[];
    list: SourceRow[];
    total: number;
    truncated: boolean;
  }
  export interface RowActionRequest {
    auditLogId: Id;
    action: 'IGNORE' | 'REMARK';
    rows: { revision: number; rowKey: string }[];
    remark?: string;
  }
  export interface PageRequest extends PageParam {
    instanceId?: Id;
    issueStatus?: string;
    orgId?: Id;
    ruleId?: Id;
    statScope?: string;
    tableName?: string;
    taskId?: Id;
  }
  /** 问题数据 */
  export interface IssueData {
    id?: Id;
    instanceId?: Id;
    auditLogId?: Id;
    taskId?: Id;
    taskName?: string;
    ruleId?: Id;
    ruleName?: string;
    objectId?: Id;
    tableName?: string;
    checkField?: string;
    orgId?: Id;
    regionCode?: string;
    statScope?: string;
    issueCount?: Id;
    pkValue?: string;
    locationSql?: string;
    issueStatus?: string;
    checkTime?: number;
  }
}

/** 受当前权限约束的回源问题行，定位键由服务端签发。 */
export function getIssueRows(data: QualityIssueDataApi.RowsRequest) {
  return requestClient.post<QualityIssueDataApi.RowsResult>(
    '/governance/quality-issue-data/rows',
    data,
  );
}

/** 处置版本由服务端校验，不提交源数据值或 SQL。 */
export function actOnIssueRows(data: QualityIssueDataApi.RowActionRequest) {
  return requestClient.post<boolean>(
    '/governance/quality-issue-data/row-action',
    data,
  );
}

/** 问题数据分页 */
export function getIssueDataPage(params: QualityIssueDataApi.PageRequest) {
  return requestClient.get<PageResult<QualityIssueDataApi.IssueData>>(
    '/governance/quality-issue-data/page',
    { params },
  );
}

/** 问题数据详情 */
export function getIssueData(id: QualityIssueDataApi.Id) {
  return requestClient.get<QualityIssueDataApi.IssueData>(
    '/governance/quality-issue-data/get',
    { params: { id } },
  );
}

/** 查看问题明细（实时回源查询） */
export function getIssueDetail(id: QualityIssueDataApi.Id, limit?: number) {
  return requestClient.get<Record<string, unknown>[]>(
    '/governance/quality-issue-data/detail',
    { params: { id, limit } },
  );
}

/** 更新问题处理状态 */
export function updateIssueStatus(
  id: QualityIssueDataApi.Id,
  issueStatus: string,
) {
  return requestClient.put<boolean>(
    '/governance/quality-issue-data/update-status',
    null,
    { params: { id, issueStatus } },
  );
}

/** 删除问题数据 */
export function deleteIssueData(id: QualityIssueDataApi.Id) {
  return requestClient.delete<boolean>(
    '/governance/quality-issue-data/delete',
    { params: { id } },
  );
}

/** 导出问题数据清单 Excel */
export function exportIssueData(params: {
  issueStatus?: string;
  statScope?: string;
  tableName?: string;
  taskId?: QualityIssueDataApi.Id;
}) {
  return requestClient.download('/governance/quality-issue-data/export-excel', {
    params,
  });
}
