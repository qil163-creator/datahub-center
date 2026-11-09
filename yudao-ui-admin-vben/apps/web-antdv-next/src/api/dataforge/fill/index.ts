import { requestClient } from '#/api/request';

export namespace DataforgeFillApi {
  /** 组织填报实例（工作项） */
  export interface WorkItem {
    id: number;
    taskId: number;
    taskName?: string;
    planId?: number;
    tableVersionId?: number;
    periodKey?: string;
    reportingOrgId?: number;
    orgName?: string;
    /** 0待填报 1填报中 2提交校验中 3待启动审批 4审批中 5整改中 6待合并 7合并中 8合并失败 9已合并 10已取消 */
    status: number;
    overdue?: number;
    submissionType?: number;
    attemptCount?: number;
    draftRowCount?: number;
    deadlineTime?: string;
    lastModifyTime?: string;
    assignees?: {
      state: number;
      userId: number;
      userName?: string;
    }[];
  }

  /** 草稿行 */
  export interface DraftRow {
    rowId: string;
    rowOrder: number | string;
    data: Record<string, unknown>;
    rowVersion: number;
    createUserId?: number;
    lastModifyUserId?: number;
  }

  /** 草稿全量快照 */
  export interface DraftSnapshot {
    workItemId: number;
    taskName?: string;
    periodKey?: string;
    orgName?: string;
    status: number;
    overdue?: number;
    deadlineTime?: string;
    serverVersion: number;
    editable: boolean;
    fieldSchema: string;
    formulaSchema?: string;
    validationSchema?: string;
    rows: DraftRow[];
  }

  /** 草稿操作（增量拉取） */
  export interface Operation {
    serverVersion: number;
    clientOpId: string;
    clientSessionId?: string;
    opType: 'ADD_ROW' | 'REMOVE_ROW' | 'SET_CELL' | string;
    rowId?: string;
    fieldCode?: string;
    newValue?: unknown;
    operatorUserId: number;
    createTime?: string;
  }

  /** 操作应用请求 */
  export interface OperationApplyReq {
    expectedVersion: number;
    clientSessionId?: string;
    operations: {
      afterRowId?: string;
      clientOpId: string;
      fieldCode?: string;
      opType: string;
      rowId?: string;
      value?: unknown;
    }[];
  }

  /** 操作应用响应（ACK） */
  export interface OperationApplyResp {
    serverVersion: number;
    appliedCount: number;
    duplicateCount: number;
    draftRowCount: number;
  }

  /** 校验问题 */
  export interface ValidationIssue {
    ruleCode: string;
    severity: number;
    rowId?: string;
    fieldCode?: string;
    displayRowNo?: number;
    message: string;
  }

  /** 提交响应 */
  export interface SubmitResp {
    success: boolean;
    submissionId?: number;
    attemptNo?: number;
    rowCount?: number;
    issues?: ValidationIssue[];
  }

  /** 提交记录 */
  export interface Submission {
    id: number;
    workItemId: number;
    taskId: number;
    attemptNo: number;
    /** 1正常 2零上报 3更正 */
    submissionType: number;
    rowCount: number;
    snapshotHash?: string;
    submitterUserId: number;
    submitTime?: string;
    processInstanceId?: string;
    /** 0待启动 1审批中 2通过 3驳回 4已撤回 5已终止 */
    approvalStatus: number;
    approvalEndTime?: string;
    approvalReason?: string;
    /** 零上报说明（submissionType=2 时填写） */
    emptyReason?: string;
  }

  /** 提交快照预览 */
  export interface SubmissionPreview extends Submission {
    taskName?: string;
    periodKey?: string;
    orgName?: string;
    fieldSchema: string;
    rows: {
      data: Record<string, unknown>;
      rowId: string;
      rowOrder: number | string;
    }[];
    /** 同一填报实例上一次提交的编号；首次提交为空 */
    previousSubmissionId?: number | string;
    /** 提交时产生的警告（不阻断提交） */
    warnings?: ValidationIssue[];
  }

  /** 采集计划目标组织 */
  export interface PlanOrg {
    reportingOrgId: number;
    orgName?: string;
    sort?: number;
    assignees?: { userId: number; userName?: string }[];
  }

  /** 采集计划 */
  export interface Plan {
    id: number | string;
    name: string;
    categoryId?: number | string;
    categoryName?: string;
    description?: string;
    tableId: number;
    tableName?: string;
    tableVersionId: number;
    tableVersionNo?: number;
    /** 1一次性 2每日 3每周 4每月 5每季度 */
    periodType: number;
    periodConfig?: string;
    timeZone?: string;
    remindConfig?: string;
    /** 1软逾期 2硬截止 */
    overduePolicy?: number;
    processDefinitionKey: string;
    publishApprovalEnabled?: number;
    prefillLastPeriod?: number;
    ownerOrgId?: number;
    /** 0草稿 1启用 2停用 */
    status: number;
    lastPeriodKey?: string;
    nextGenerateTime?: string;
    creator?: string;
    createTime?: string;
    orgs?: PlanOrg[];
  }

  /** 采集计划保存请求 */
  export interface PlanSaveReq {
    id?: number | string;
    name: string;
    categoryId?: null | number | string;
    description?: string;
    tableId: number | string;
    tableVersionId: number | string;
    periodType: number;
    periodConfig?: string;
    timeZone?: string;
    overduePolicy?: number;
    processDefinitionKey: string;
    prefillLastPeriod?: number;
    remindConfig?: string;
    orgs: {
      assigneeUserIds: (number | string)[];
      reportingOrgId: number | string;
      sort?: number;
    }[];
  }

  /** 采集计划分类 */
  export interface PlanCategory {
    id: number | string;
    parentId: number | string;
    name: string;
    sort: number;
  }

  export interface PlanCategorySaveReq {
    id?: number | string;
    parentId: number | string;
    name: string;
    sort: number;
  }

  /** 采集任务 */
  export interface Task {
    id: number;
    planId: number;
    name: string;
    tableId: number;
    tableVersionId: number;
    processDefinitionKey?: string;
    /** 冻结的审批流程版本；为空表示首次启动审批时冻结 */
    processDefinitionId?: string;
    periodKey: string;
    periodStart?: string;
    periodEnd?: string;
    openTime?: string;
    deadlineTime?: string;
    overduePolicy?: number;
    /** 0待开始 1进行中 2已完成 3已取消 */
    status: number;
    totalOrgCount: number;
    mergedOrgCount: number;
    createTime?: string;
  }

  /** 任务进度 */
  export interface TaskProgress {
    taskId: number;
    totalOrgCount: number;
    mergedCount: number;
    overdueCount: number;
    completionRate: number;
    statusCounts: Record<number, number>;
  }

  /** 模板版本 */
  export interface TableVersion {
    id: number;
    tableId: number;
    versionNo: number;
    name?: string;
    /** 1已发布 2已停用 */
    status: number;
    mergeStrategy?: string;
    businessKeyFields?: string;
    contentHash?: string;
    releaseTime?: string;
    createTime?: string;
  }

  /** 数据更正实例 */
  export interface Correction {
    id: number;
    originSubmissionId: number;
    workItemId: number;
    orgName?: string;
    taskId: number;
    taskName?: string;
    reason?: string;
    correctionSubmissionId?: number;
    /** 0草拟 1审批中 2已合并 3已取消 */
    status: number;
    creator?: string;
    createTime?: string;
  }

  /** Excel 导入预检结果 */
  export interface ExcelImportPreview {
    totalCount: number;
    errorCount: number;
    matchedColumns: string[];
    ignoredHeaders: string[];
    issues: ValidationIssue[];
    rows: Record<string, unknown>[];
  }

  /** 合并批次 */
  export interface MergeBatch {
    id: number;
    submissionId: number;
    workItemId: number;
    taskId: number;
    mergeStrategy: string;
    targetDatabaseId: number;
    targetTableName: string;
    /** 0待执行 1执行中 2成功 3失败 */
    status: number;
    totalCount: number;
    successCount: number;
    errorMessage?: string;
    retryCount: number;
    nextRetryTime?: string;
    startedAt?: string;
    finishedAt?: string;
    createTime?: string;
  }
}

/** 我的填报：当前用户作为有效填报人的工作项 */
export function getMyWorkItems() {
  return requestClient.get<DataforgeFillApi.WorkItem[]>(
    '/dataforge/fill-work-item/my',
  );
}

/** 工作项详情 */
export function getWorkItem(id: number) {
  return requestClient.get<DataforgeFillApi.WorkItem>(
    `/dataforge/fill-work-item/get?id=${id}`,
  );
}

/** 草稿全量快照（进入工作台/断线重连） */
export function getDraftSnapshot(workItemId: number | string) {
  return requestClient.get<DataforgeFillApi.DraftSnapshot>(
    `/dataforge/fill-work-item/${workItemId}/snapshot`,
  );
}

/** 增量拉取草稿操作 */
export function getDraftOperations(
  workItemId: number | string,
  afterVersion: number,
  limit = 200,
) {
  return requestClient.get<DataforgeFillApi.Operation[]>(
    `/dataforge/fill-work-item/${workItemId}/operations?afterVersion=${afterVersion}&limit=${limit}`,
  );
}

/** 应用一批草稿操作（单事务原子 + clientOpId 幂等） */
export function applyDraftOperations(
  workItemId: number | string,
  data: DataforgeFillApi.OperationApplyReq,
) {
  return requestClient.request<DataforgeFillApi.OperationApplyResp>(
    `/dataforge/fill-work-item/${workItemId}/operations`,
    { data, method: 'PATCH' },
  );
}

/** 填报职责转派 */
export function transferWorkItem(data: {
  reason?: string;
  targetUserId: number;
  workItemId: number | string;
}) {
  return requestClient.post('/dataforge/fill-work-item/transfer', data);
}

/** 提交填报数据（冻结草稿 → 强校验 → 快照 → 启动审批） */
export function submitWorkItem(
  workItemId: number | string,
  data: {
    emptyReason?: string;
    expectedVersion: number;
    submissionType?: number;
  },
) {
  return requestClient.post<DataforgeFillApi.SubmitResp>(
    `/dataforge/fill-work-item/${workItemId}/submit`,
    data,
  );
}

/** 工作项的全部提交记录 */
export function getSubmissionsByWorkItem(workItemId: number | string) {
  return requestClient.get<DataforgeFillApi.Submission[]>(
    `/dataforge/fill-work-item/${workItemId}/submissions`,
  );
}

/** 提交快照只读预览（审批人从 BPM 业务表单打开） */
export function getSubmissionPreview(id: number | string) {
  return requestClient.get<DataforgeFillApi.SubmissionPreview>(
    `/dataforge/fill-submission/preview?id=${id}`,
  );
}

/** 撤回审批中的提交（仅实际提交人） */
export function withdrawSubmission(id: number, reason?: string) {
  return requestClient.put(
    `/dataforge/fill-submission/withdraw?id=${id}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`,
  );
}

/** 合并批次分页 */
export function getMergeBatchPage(params: {
  pageNo: number;
  pageSize: number;
  status?: number;
  targetTableName?: string;
  taskId?: number;
}) {
  return requestClient.get<{
    list: DataforgeFillApi.MergeBatch[];
    total: number;
  }>('/dataforge/fill-merge-batch/page', { params });
}

/** 重试失败的合并批次 */
export function retryMergeBatch(id: number) {
  return requestClient.put(`/dataforge/fill-merge-batch/retry?id=${id}`);
}

// ==================== 采集计划 ====================

/** 采集计划分页 */
export function getPlanPage(params: {
  categoryId?: number | string;
  name?: string;
  pageNo: number;
  pageSize: number;
  periodType?: number;
  status?: number;
  tableId?: number;
}) {
  return requestClient.get<{ list: DataforgeFillApi.Plan[]; total: number }>(
    '/dataforge/fill-plan/page',
    { params },
  );
}

/** 采集计划详情（含目标组织与填报人） */
export function getPlan(id: number | string) {
  return requestClient.get<DataforgeFillApi.Plan>('/dataforge/fill-plan/get', {
    params: { id },
  });
}

/** 创建采集计划 */
export function createPlan(data: DataforgeFillApi.PlanSaveReq) {
  return requestClient.post<number | string>(
    '/dataforge/fill-plan/create',
    data,
  );
}

/** 更新采集计划 */
export function updatePlan(data: DataforgeFillApi.PlanSaveReq) {
  return requestClient.put('/dataforge/fill-plan/update', data);
}

/** 删除采集计划（仅草稿/停用） */
export function deletePlan(id: number | string) {
  return requestClient.delete(`/dataforge/fill-plan/delete?id=${id}`);
}

/** 发布（启用）采集计划 */
export function publishPlan(id: number | string) {
  return requestClient.put(`/dataforge/fill-plan/publish?id=${id}`);
}

/** 停用采集计划 */
export function disablePlan(id: number | string) {
  return requestClient.put(`/dataforge/fill-plan/disable?id=${id}`);
}

/** 查询采集计划分类，返回平铺树节点 */
export function getPlanCategoryList() {
  return requestClient.get<DataforgeFillApi.PlanCategory[]>(
    '/dataforge/fill-plan-category/list',
  );
}

/** 查询采集计划分类详情 */
export function getPlanCategory(id: number | string) {
  return requestClient.get<DataforgeFillApi.PlanCategory>(
    '/dataforge/fill-plan-category/get',
    { params: { id } },
  );
}

/** 创建采集计划分类 */
export function createPlanCategory(data: DataforgeFillApi.PlanCategorySaveReq) {
  return requestClient.post<number | string>(
    '/dataforge/fill-plan-category/create',
    data,
  );
}

/** 更新采集计划分类 */
export function updatePlanCategory(data: DataforgeFillApi.PlanCategorySaveReq) {
  return requestClient.put<boolean>(
    '/dataforge/fill-plan-category/update',
    data,
  );
}

/** 删除采集计划分类 */
export function deletePlanCategory(id: number | string) {
  return requestClient.delete<boolean>('/dataforge/fill-plan-category/delete', {
    params: { id },
  });
}

// ==================== 采集任务 ====================

/** 采集任务分页 */
export function getTaskPage(params: {
  name?: string;
  pageNo: number;
  pageSize: number;
  periodKey?: string;
  planId?: number;
  status?: number;
}) {
  return requestClient.get<{ list: DataforgeFillApi.Task[]; total: number }>(
    '/dataforge/fill-task/page',
    { params },
  );
}

/** 采集任务详情 */
export function getTask(id: number) {
  return requestClient.get<DataforgeFillApi.Task>(
    `/dataforge/fill-task/get?id=${id}`,
  );
}

/** 任务进度（各状态数量、逾期数、完成率） */
export function getTaskProgress(id: number) {
  return requestClient.get<DataforgeFillApi.TaskProgress>(
    `/dataforge/fill-task/progress?id=${id}`,
  );
}

/** 手工生成/补生成任务 */
export function generateTask(data: {
  generateTime?: string;
  planId: number | string;
}) {
  return requestClient.post<number>('/dataforge/fill-task/generate', data);
}

/** 任务下的全部工作项（发起者进度视角） */
export function getWorkItemListByTask(taskId: number) {
  return requestClient.get<DataforgeFillApi.WorkItem[]>(
    `/dataforge/fill-work-item/list-by-task?taskId=${taskId}`,
  );
}

/** 部门负责人只读查看本部门工作项 */
export function getDeptWorkItems(deptId?: number) {
  return requestClient.get<DataforgeFillApi.WorkItem[]>(
    `/dataforge/fill-work-item/dept-view${deptId ? `?deptId=${deptId}` : ''}`,
  );
}

/** 调整填报人（发起者/授权管理员） */
export function updateWorkItemAssignees(data: {
  addUserIds?: number[];
  reason?: string;
  removeUserIds?: number[];
  workItemId: number;
}) {
  return requestClient.put('/dataforge/fill-work-item/assignees', data);
}

// ==================== 模板版本 ====================

/** 填报表的模板版本列表 */
export function getTableVersionListByTable(tableId: number | string) {
  return requestClient.get<DataforgeFillApi.TableVersion[]>(
    '/dataforge/fill-table-version/list-by-table',
    { params: { tableId } },
  );
}

// ==================== Excel 导入导出 ====================

/** 下载 Excel 填报模板（按模板版本生成，含必填标记与字典下拉） */
export function downloadWorkItemExcelTemplate(workItemId: number | string) {
  return requestClient.download(
    `/dataforge/fill-work-item/${workItemId}/excel/template`,
  );
}

/** Excel 导入预检（不写草稿） */
export function previewWorkItemExcelImport(
  workItemId: number | string,
  file: File,
) {
  const formData = new FormData();
  formData.append('file', file);
  return requestClient.post<DataforgeFillApi.ExcelImportPreview>(
    `/dataforge/fill-work-item/${workItemId}/excel/import/preview`,
    formData,
  );
}

/** Excel 导入确认（追加或整体替换，经草稿操作通道原子写入） */
export function confirmWorkItemExcelImport(
  workItemId: number | string,
  data: {
    expectedVersion: number;
    mode: number;
    rows: Record<string, unknown>[];
  },
) {
  return requestClient.post<DataforgeFillApi.OperationApplyResp>(
    `/dataforge/fill-work-item/${workItemId}/excel/import/confirm`,
    data,
  );
}

/** 导出当前草稿数据 */
export function exportWorkItemExcel(workItemId: number | string) {
  return requestClient.download(
    `/dataforge/fill-work-item/${workItemId}/excel/export`,
  );
}

// ==================== 数据更正 ====================

/** 发起数据更正（仅最新已合并快照；实例转入整改中重新走审批） */
export function createCorrection(data: {
  reason: string;
  submissionId: number;
}) {
  return requestClient.post<number>('/dataforge/fill-correction/create', data);
}

/** 取消更正（仅草拟中且尚未重新提交） */
export function cancelCorrection(id: number) {
  return requestClient.put(`/dataforge/fill-correction/cancel?id=${id}`);
}

/** 数据更正分页 */
export function getCorrectionPage(params: {
  pageNo: number;
  pageSize: number;
  status?: number;
  taskId?: number;
}) {
  return requestClient.get<{
    list: DataforgeFillApi.Correction[];
    total: number;
  }>('/dataforge/fill-correction/page', { params });
}

// ==================== 催办 ====================

/** 催办单个组织实例（1 小时频控） */
export function urgeWorkItem(id: number) {
  return requestClient.post(`/dataforge/fill-work-item/urge?id=${id}`);
}

/** 任务级一键催办，返回实际催办组织数 */
export function urgeTask(id: number) {
  return requestClient.post<number>(`/dataforge/fill-task/urge?id=${id}`);
}

/** 把任务冻结的审批流程切换到当前生效版本，返回新的流程定义编号 */
export function switchTaskProcessDefinition(id: number) {
  return requestClient.put<string>(
    `/dataforge/fill-task/switch-process-definition?id=${id}`,
  );
}
