import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityTaskTemplateApi {
  /** 任务模板 */
  export interface Template {
    id?: number;
    templateName?: string;
    templateCode?: string;
    datasourceConnId?: number;
    ruleSourceType?: string;
    ruleSetId?: number;
    ruleSetName?: string;
    ruleIds?: string;
    tableScopeType?: string;
    tableMatch?: string;
    tableIds?: string;
    layer?: string;
    fieldSelectStrategy?: string;
    scheduleType?: string;
    cronExpression?: string;
    syncMode?: string;
    taskLimit?: number;
    orgField?: string;
    groupField?: string;
    status?: number;
    description?: string;
    generatedTaskCount?: number;
    createTime?: string;
  }
}

/** 任务模板分页 */
export function getTaskTemplatePage(
  params: PageParam & {
    datasourceConnId?: number;
    status?: number;
    templateName?: string;
  },
) {
  return requestClient.get<PageResult<QualityTaskTemplateApi.Template>>(
    '/governance/quality-task-template/page',
    { params },
  );
}

/** 任务模板详情 */
export function getTaskTemplate(id: number) {
  return requestClient.get<QualityTaskTemplateApi.Template>(
    `/governance/quality-task-template/get?id=${id}`,
  );
}

/** 新增任务模板 */
export function createTaskTemplate(data: QualityTaskTemplateApi.Template) {
  return requestClient.post('/governance/quality-task-template/create', data);
}

/** 修改任务模板 */
export function updateTaskTemplate(data: QualityTaskTemplateApi.Template) {
  return requestClient.put('/governance/quality-task-template/update', data);
}

/** 修改任务模板状态 */
export function updateTaskTemplateStatus(id: number, status: number) {
  return requestClient.put(
    `/governance/quality-task-template/update-status?id=${id}&status=${status}`,
  );
}

/** 删除任务模板 */
export function deleteTaskTemplate(id: number) {
  return requestClient.delete(
    `/governance/quality-task-template/delete?id=${id}`,
  );
}

/** 同步物化：按模板批量生成检测任务 */
export function materializeTaskTemplate(id: number) {
  return requestClient.post<number>(
    `/governance/quality-task-template/materialize?id=${id}`,
  );
}
