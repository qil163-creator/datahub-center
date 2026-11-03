import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityTaskInstanceApi {
  /** 后端 Long：安全范围内为 number，超出时为十进制字符串 */
  export type Id = number | string;

  /** ETL 受控批次来源，仅 ETL 目标批次触发的实例返回；不含范围清单与业务键 */
  export interface EtlBatch {
    recordId: Id;
    targetBatchId: Id;
    sinkNodeId?: string;
    qualityNodeId?: string;
    qualityBatchId?: string;
    inlineReportId?: Id;
    rowCount?: Id;
    admittedRowCount?: Id;
    allowWarnings?: boolean;
    state?: string;
    replayOfBatchId?: Id | null;
    replayOfRecordId?: Id | null;
    workOrderSubmissionId?: Id | null;
    runId?: string;
  }

  /** 任务实例 */
  export interface Instance {
    id?: Id;
    taskId?: Id;
    taskName?: string;
    instanceName?: string;
    /** MANUAL / SCHEDULE / ETL_TARGET_BATCH / DATAFORGE */
    runType?: string;
    /** QUEUED / RUNNING / SUCCESS / FAILED / UNKNOWN */
    instanceStatus?: string;
    scheduleTime?: number;
    startTime?: number;
    endTime?: number;
    durationMs?: number;
    runCount?: number;
    errorFlag?: string;
    execUser?: string;
    host?: string;
    totalCount?: Id;
    passCount?: Id;
    failCount?: Id;
    passRate?: string;
    score?: string;
    createTime?: number;
    etlBatch?: EtlBatch | null;
  }
}

/** 任务实例分页 */
export function getTaskInstancePage(
  params: PageParam & {
    instanceName?: string;
    instanceStatus?: string;
    runType?: string;
    taskId?: QualityTaskInstanceApi.Id;
  },
) {
  return requestClient.get<PageResult<QualityTaskInstanceApi.Instance>>(
    '/governance/quality-task-instance/page',
    { params },
  );
}

/** 任务实例详情，ETL 批次触发的实例附带 etlBatch 来源 */
export function getTaskInstance(id: QualityTaskInstanceApi.Id) {
  return requestClient.get<QualityTaskInstanceApi.Instance>(
    '/governance/quality-task-instance/get',
    { params: { id } },
  );
}
