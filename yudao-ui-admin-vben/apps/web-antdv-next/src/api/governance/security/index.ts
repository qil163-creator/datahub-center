import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetySecurityApi {
  /** 敏感数据访问审计日志 */
  export interface AccessLog {
    id: number | string;
    userId?: number | string;
    userName?: string;
    appId?: number | string;
    accessType?: string; // API / STATIC_TASK / QUERY / DEV / EXPLORE / ACCESS
    resourceName?: string;
    databaseId?: number | string;
    tableName?: string;
    sensitiveColumns?: string;
    columnCount?: number;
    maskedFlag?: number; // 1 已脱敏 0 明文豁免
    whitelistId?: number | string;
    callerIp?: string;
    accessTime?: number | string;
  }
}

/** 查询访问审计分页 */
export function getAccessLogPage(params: PageParam) {
  return requestClient.get<PageResult<SafetySecurityApi.AccessLog>>(
    '/governance/safety-access-log/page',
    { params },
  );
}
