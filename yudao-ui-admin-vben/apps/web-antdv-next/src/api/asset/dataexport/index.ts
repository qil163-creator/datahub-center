import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace DataExportApi {
  export type Status =
    | 'APPROVED'
    | 'CANCELED'
    | 'DOWNLOADED'
    | 'FAILED'
    | 'PENDING'
    | 'REJECTED';

  /** 导出申请 */
  export interface Request {
    id: number | string;
    datasourceId: number | string;
    tableName?: string;
    sqlText?: string;
    rowLimit: number;
    reason: string;
    status: Status;
    processInstanceId?: string;
    resultReason?: string;
    approvedTime?: number | string;
    expireTime?: number | string;
    downloadTime?: number | string;
    downloadRows?: number;
    createTime?: number | string;
  }

  export interface SubmitReq {
    datasourceId: number | string;
    sql: string;
    limit: number;
    reason: string;
  }
}

/** 提交导出申请（走审批流程） */
export function submitDataExport(data: DataExportApi.SubmitReq) {
  return requestClient.post<number | string>('/asset/data-export/submit', data);
}

/** 本人的导出申请 */
export function getMyDataExportPage(params: PageParam) {
  return requestClient.get<PageResult<DataExportApi.Request>>(
    '/asset/data-export/my-page',
    { params },
  );
}

/** 撤回审批中的申请 */
export function cancelDataExport(id: number | string) {
  return requestClient.put<boolean>('/asset/data-export/cancel', undefined, {
    params: { id },
  });
}

/** 下载已批准的导出（一次性，下载后即失效） */
export function downloadDataExport(id: number | string) {
  return requestClient.download<Blob>('/asset/data-export/download', {
    params: { id },
  });
}
