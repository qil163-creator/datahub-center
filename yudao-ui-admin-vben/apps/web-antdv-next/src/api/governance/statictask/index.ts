import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyStaticTaskApi {
  export type Id = number | string;
  /** 字段脱敏配置项 */
  export interface FieldRule {
    columnName: string;
    ruleId: Id;
  }

  /** 源表字段 */
  export interface TableColumn {
    columnName: string;
    typeName?: string;
  }

  /** 静态脱敏任务 */
  export interface Task {
    id?: Id;
    taskName?: string;
    sourceDatabaseId?: Id;
    sourceTableName?: string;
    targetDatabaseId?: Id;
    targetTableName?: string;
    writeMode?: string;
    batchSize?: number;
    fieldRules?: FieldRule[];
    cronExpression?: string;
    status?: number;
    description?: string;
    createTime?: number;
  }

  /** 执行记录 */
  export interface TaskLog {
    id: Id;
    taskId: Id;
    runType?: number;
    runStatus?: number;
    startTime?: number;
    endTime?: number;
    dataCount?: number;
    maskedColumnCount?: number;
    errorInfo?: string;
  }
}

/** 查询静态脱敏任务分页 */
export function getSafetyStaticTaskPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyStaticTaskApi.Task>>(
    '/governance/safety-static-task/page',
    { params },
  );
}

/** 查询静态脱敏任务详情 */
export function getSafetyStaticTask(id: SafetyStaticTaskApi.Id) {
  return requestClient.get<SafetyStaticTaskApi.Task>(
    `/governance/safety-static-task/get?id=${id}`,
  );
}

/** 新增静态脱敏任务 */
export function createSafetyStaticTask(data: SafetyStaticTaskApi.Task) {
  return requestClient.post('/governance/safety-static-task/create', data);
}

/** 修改静态脱敏任务 */
export function updateSafetyStaticTask(data: SafetyStaticTaskApi.Task) {
  return requestClient.put('/governance/safety-static-task/update', data);
}

/** 更新上下线状态 */
export function updateSafetyStaticTaskStatus(id: SafetyStaticTaskApi.Id, status: number) {
  return requestClient.put(
    '/governance/safety-static-task/update-status',
    null,
    {
      params: { id, status },
    },
  );
}

/** 删除静态脱敏任务 */
export function deleteSafetyStaticTask(id: SafetyStaticTaskApi.Id) {
  return requestClient.delete(`/governance/safety-static-task/delete?id=${id}`);
}

/** 批量删除静态脱敏任务 */
export function deleteSafetyStaticTaskList(ids: SafetyStaticTaskApi.Id[]) {
  return requestClient.delete(
    `/governance/safety-static-task/delete-list?ids=${ids.join(',')}`,
  );
}

/** 手动执行静态脱敏任务（异步） */
export function executeSafetyStaticTask(id: SafetyStaticTaskApi.Id) {
  return requestClient.post('/governance/safety-static-task/execute', null, {
    params: { id },
  });
}

/** 查询执行记录分页 */
export function getSafetyStaticTaskLogPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyStaticTaskApi.TaskLog>>(
    '/governance/safety-static-task/log/page',
    { params },
  );
}

/** 查询源表字段列表 */
export function getSafetyStaticTaskColumnList(
  databaseId: SafetyStaticTaskApi.Id,
  tableName: string,
) {
  return requestClient.get<SafetyStaticTaskApi.TableColumn[]>(
    '/governance/safety-static-task/column-list',
    { params: { databaseId, tableName } },
  );
}
