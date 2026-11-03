import type { PageParam, PageResult } from '@vben/request';

import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';
import type {
  ResolveCommand,
  RunCommand,
  RunId,
  RunReceipt,
  StopCommand,
} from '#/api/dataforge/execution/command';

import { requestClient } from '#/api/request';

export namespace DataforgeAccessApi {
  export interface GroupRecoveryState {
    recordId: RunId;
    rowVersion: RunId;
    applicable: boolean;
    ready: boolean;
    released: boolean;
    message: string;
  }

  export interface GroupRecoverCommand {
    recordId: RunId;
    expectedVersion: RunId;
    requestId: string;
    reason: string;
    evidenceRef: string;
  }

  /** 名称映射规则（正则） */
  export interface PatternMapper {
    fromPattern?: string;
    toValue?: string;
  }

  /** Doris 静态分区，RANGE values 为 [下界元组, 上界元组]。所有边界保持字符串。 */
  export interface DorisPartition {
    kind: 'LIST' | 'RANGE';
    columns: string[];
    partitions: { name: string; values: string[][] }[];
  }

  export interface DorisOptions {
    partition?: DorisPartition;
    distributionType: 'HASH' | 'RANDOM';
    distributionColumns: string[];
    bucketMode: 'AUTO' | 'FIXED';
    bucketCount?: number;
    replicationNum: number;
  }

  /** 单表配置；显式目标名为最终物理名称，未设置时沿用原名称映射规则。 */
  export interface TableMapping {
    targetTableName?: string;
    targetTableComment?: string;
    dorisOptions?: DorisOptions;
    regexTableMapper?: PatternMapper[];
    regexColumnMapper?: PatternMapper[];
    sourcePrimaryKeys?: string[];
    increaseColumnName?: string;
    timestampColumn?: string;
  }

  /** API 接入字段映射项 */
  export interface ApiColumnMap {
    sourceColumn?: string;
    targetColumn?: string;
    importFlg?: number; // 0-否 1-是
  }

  /** API 接入映射（一个 API → 一张目标表） */
  export interface ApiAccessMapping {
    apiConfig?: DataforgeDataApiApi.DataApi;
    sourceApiName?: string;
    targetTable?: string;
    columnMaps?: ApiColumnMap[];
  }

  export type TypeFidelity = 'EXACT' | 'LOSSY' | 'UNSUPPORTED';

  /** 目标表名/字段名转换方式，与后端白名单一致 */
  export type NameCase = 'CAMEL' | 'LOWER' | 'NONE' | 'SNAKE' | 'UPPER';

  export interface TypeMappingAcknowledgement {
    targetProduct: string;
    targetSchema: string;
    targetTable: string;
    column: string;
    mappingFingerprint: string;
    lossCodes: string[];
  }

  export interface TypeMappingColumn {
    column: string;
    sourceNativeType: null | string;
    logicalType: null | string;
    targetNativeType?: null | string;
    fidelity: TypeFidelity;
    accepted: boolean;
    mappingFingerprint: string;
    lossCodes: string[];
    issues: { code: string; detail: string; severity: string }[];
  }

  export interface TypeMappingPlan {
    schemaVersion: 1;
    totalColumns: number;
    truncated: boolean;
    sourceProduct: string;
    sourceSchema: string;
    sourceTable: string;
    targetProduct: string;
    targetSchema: string;
    targetTable: string;
    fidelity: TypeFidelity;
    accepted: boolean;
    columns: TypeMappingColumn[];
  }

  /** 数据同步任务 */
  export interface DataAccess {
    /** 可选生命周期脚本；执行前逐条治理授权，当前写入脚本不开放。 */
    sourceBeforeSqlScripts?: null | string;
    sourceAfterSqlScripts?: null | string;
    targetBeforeSqlScripts?: null | string;
    targetAfterSqlScripts?: null | string;
    schemaVersion?: 1;
    /** 仅保存用户逐列确认的有损映射；默认空，执行前重新校验。 */
    typeMappingAcknowledgements?: TypeMappingAcknowledgement[];
    /** 业务声明；固定驱动、可信源代次和键兼容性由服务端另行验证，不接受浏览器提供代次。 */
    cursorWatermarkContract?: 'MONOTONIC_WATERMARK_V1';
    /** 仅有旧字符串水位而无类型化检查点时，明确允许从头读取；不转换旧水位。 */
    cursorBootstrapAllowed?: boolean;
    identitySourceQuiesced?: boolean;
    preserveIdentityValues?: boolean;
    exclusiveTargetOwnership?: boolean;
    tableReadMode?: 'INCLUDE_DESCENDANTS' | 'ONLY';
    writeMode?: 'CREATE_NEW' | 'STRICT_COPY' | null;
    failOnFieldReadError?: boolean;
    targetSyncOption?:
      | 'DELETE'
      | 'INSERT'
      | 'INSERT_DELETE'
      | 'INSERT_UPDATE'
      | 'INSERT_UPDATE_DELETE'
      | 'UPDATE'
      | 'UPDATE_DELETE';
    id?: RunId;
    taskName?: string; // 任务名称
    description?: string; // 描述
    orgId?: RunId; // 单位id
    orgName?: string; // 单位名称
    engineType?: number; // 同步引擎类型：1-离线同步(DBSwitch) 2-实时同步(SeaTunnel) 3-API接入
    sourceType?: number; // 源端类型：1-指定表 2-SQL
    sourceDatabaseId?: RunId; // 源端数据源id
    sourceDatabaseName?: string;
    targetDatabaseId?: RunId; // 目的端数据源id
    targetDatabaseName?: string;
    accessMode?: number; // 接入方式：1-入中台库 2-自定义接入
    taskType?: number; // 任务类型：2-一次性全量 3-周期增量
    cron?: string; // cron 表达式
    status?: number; // 发布状态：0-未发布 1-已发布
    runStatus?: number; // 运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束
    includeOrExclude?: number; // 1-包含 0-排除
    sourceSelectedTables?: string[]; // 源端选择的表
    targetOnlyCreate?: boolean; // 只创建表结构
    targetSyncExit?: boolean; // 同步已存在的表
    targetDropTable?: boolean; // 同步前删除重建
    targetTruncateData?: boolean; // 同步前清空数据
    targetDataSync?: boolean; // 增量变更同步
    changeDataSyncType?: string; // PK_ORDER_COMPARE / INCREASE_COLUMN
    targetIndexCreate?: boolean; // 同步索引
    syncDefaultVal?: boolean; // 同步默认值
    targetLowerCase?: boolean; // 旧版开关：表名字段名转小写，仅在对应转换方式为空时生效
    targetUpperCase?: boolean; // 旧版开关：表名字段名转大写，仅在对应转换方式为空时生效
    targetTableNameCase?: NameCase | null; // 表名转换；旧任务为空
    targetColumnNameCase?: NameCase | null; // 字段名转换；旧任务为空
    targetAutoIncrement?: boolean; // 主键自增
    batchSize?: number; // 批处理量
    mapperType?: string; // ALL / SINGLE
    tableNameMapper?: PatternMapper[]; // 表名映射
    columnNameMapper?: PatternMapper[]; // 字段名映射
    configMap?: Record<string, TableMapping>; // 单表映射与建表配置
    sourceSql?: string; // 源端 SQL
    targetTable?: string; // 目标表名（SQL 接入）
    sourcePrimaryKeys?: string[]; // 唯一键
    increaseColumnName?: string; // 增量字段
    timestampColumn?: string; // 时间戳字段
    seatunnelConfig?: string; // SeaTunnel 作业配置原文
    configFormat?: string; // SeaTunnel 配置格式：json / hocon
    apiMappings?: ApiAccessMapping[]; // API 接入映射
    startTime?: number;
    endTime?: number;
    releaseTime?: number;
    releaseUserId?: RunId;
    releaseUserName?: string;
    nextRunTime?: number;
    remark?: string;
    creatorName?: string;
    createTime?: number;
  }

  /** 名称映射预览请求 */
  export interface PreviewReq {
    schemaVersion?: 1;
    sourceDatabaseId?: RunId;
    targetDatabaseId?: RunId;
    sourcePrimaryKeys?: string[];
    syncDefaultVal?: boolean;
    sourceType?: number;
    sourceSql?: string;
    includeOrExclude?: number;
    sourceSelectedTables?: string[];
    accessMode?: number;
    mapperType?: string;
    tableNameMapper?: PatternMapper[];
    columnNameMapper?: PatternMapper[];
    configMap?: Record<string, TableMapping>;
    /** 旧版开关，仅在对应转换方式为空时生效 */
    targetLowerCase?: boolean;
    targetUpperCase?: boolean;
    targetTableNameCase?: NameCase;
    targetColumnNameCase?: NameCase;
    previewTableName?: string;
  }

  export type TypeMappingPreviewReq = PreviewReq;

  /** 名称映射预览结果 */
  export interface PreviewNameMapper {
    originalName: string;
    targetName: string;
    remarks?: string;
  }

  /** 执行记录 */
  export interface AccessTask {
    id: RunId;
    dataAccessId?: RunId;
    taskName?: string;
    runStatus?: number;
    runType?: number; // 1-自动调度 2-手动执行
    executeNo?: string;
    seatunnelJobId?: string; // Seatunnel 作业编号

    startTime?: number;
    endTime?: number;
    realTimeLog?: string;
    errorInfo?: string;
    dataCount?: RunId;
    tableSuccessCount?: RunId;
    tableFailCount?: RunId;
    byteCount?: string;
    createTime?: number;
    protocolVersion?: number;
    executionStage?: string;
    effectState?: string;
    stopRequested?: boolean;
    occupiesSlot?: boolean;
    reviewRequired?: boolean;
    rowVersion?: RunId;
    scheduledTime?: number;
    nextAttemptAt?: number;
    nextReconcileAt?: number;
  }

  /** 表级同步结果 */
  export interface AccessTaskDetail {
    id: RunId;
    dataAccessId?: RunId;
    taskId?: RunId;
    sourceSchemaName?: string;
    sourceTableName?: string;
    targetSchemaName?: string;
    targetTableName?: string;
    syncCount?: RunId;
    syncBytes?: string;
    ifSuccess?: number; // 0-否 1-是
    errorMsg?: string;
    successMsg?: string;
    typeMappingPlan?: null | TypeMappingPlan;
    createTime?: number;
  }

  export interface AccessPageReq extends PageParam {
    taskName?: string;
    engineType?: number;
    engineTypes?: number[];
    orgId?: RunId;
    sourceDatabaseId?: RunId;
    status?: number;
    runStatus?: number;
  }
  export interface TaskPageReq extends PageParam {
    dataAccessId?: RunId;
    taskName?: string;
    runStatus?: number;
    runType?: number;
  }
  export interface DetailPageReq extends PageParam {
    dataAccessId?: RunId;
    taskId?: RunId;
    ifSuccess?: number;
    sourceTableName?: string;
    targetTableName?: string;
  }
}

/** 查询数据同步任务分页 */
export function getAccessPage(params: DataforgeAccessApi.AccessPageReq) {
  return requestClient.get<PageResult<DataforgeAccessApi.DataAccess>>(
    '/dataforge/access/page',
    { params },
  );
}

/** 查询数据同步任务详情（含配置回显） */
export function getAccess(id: RunId) {
  return requestClient.get<DataforgeAccessApi.DataAccess>(
    '/dataforge/access/get',
    { params: { id } },
  );
}

/** 新增数据同步任务 */
export function createAccess(data: DataforgeAccessApi.DataAccess) {
  return requestClient.post<RunId>('/dataforge/access/create', data);
}

/** 修改数据同步任务 */
export function updateAccess(data: DataforgeAccessApi.DataAccess) {
  return requestClient.put<boolean>('/dataforge/access/update', data);
}

/** 删除数据同步任务 */
export function deleteAccess(id: RunId) {
  return requestClient.delete<boolean>('/dataforge/access/delete', {
    params: { id },
  });
}

/** 预览表名映射 */
export function previewTableMap(data: DataforgeAccessApi.PreviewReq) {
  return requestClient.post<DataforgeAccessApi.PreviewNameMapper[]>(
    '/dataforge/access/preview-table-map',
    data,
  );
}

/** 预览字段名映射 */
export function previewColumnMap(data: DataforgeAccessApi.PreviewReq) {
  return requestClient.post<DataforgeAccessApi.PreviewNameMapper[]>(
    '/dataforge/access/preview-column-map',
    data,
  );
}

/** 只读预览当前表实际类型映射，不接受有损转换或执行目标写入。 */
export function previewTypeMap(data: DataforgeAccessApi.TypeMappingPreviewReq) {
  return requestClient.post<DataforgeAccessApi.TypeMappingPlan>(
    '/dataforge/access/preview-type-map',
    data,
  );
}

/** 发布任务 */
export function releaseAccess(id: RunId) {
  return requestClient.put<boolean>('/dataforge/access/release', undefined, {
    params: { id },
  });
}

/** 取消发布 */
export function cancelAccess(id: RunId) {
  return requestClient.put<boolean>('/dataforge/access/cancel', undefined, {
    params: { id },
  });
}

/** 手动执行一次 */
export function runAccess(command: RunCommand) {
  return requestClient.post<RunReceipt>(
    '/dataforge/access/run-command',
    command,
  );
}

/** 停止精确执行记录；回执不表示资源已退出。 */
export function stopAccess(command: StopCommand & { reason?: string }) {
  return requestClient.post<RunReceipt>(
    '/dataforge/access/stop-command',
    command,
  );
}

export function resolveAccess(command: ResolveCommand) {
  return requestClient.post<RunReceipt>(
    '/dataforge/access/resolve-command',
    command,
  );
}

/** Platform evidence only; the recovery command independently checks permissions and ownership. */
export function getAccessGroupRecovery(recordId: RunId) {
  return requestClient.get<DataforgeAccessApi.GroupRecoveryState>(
    '/dataforge/access/group-recovery/get',
    { params: { recordId } },
  );
}

export function recoverAccessGroups(
  command: DataforgeAccessApi.GroupRecoverCommand,
) {
  return requestClient.post<RunReceipt>(
    '/dataforge/access/group-recover-command',
    command,
  );
}

/** 查询执行记录分页 */
export function getAccessTaskPage(params: DataforgeAccessApi.TaskPageReq) {
  return requestClient.get<PageResult<DataforgeAccessApi.AccessTask>>(
    '/dataforge/access/task/page',
    { params },
  );
}

/** 查询执行记录详情（含实时日志，可轮询） */
export function getAccessTask(id: RunId) {
  return requestClient.get<DataforgeAccessApi.AccessTask>(
    '/dataforge/access/task/get',
    { params: { id } },
  );
}

/** 批量删除执行记录 */
export function deleteAccessTaskList(ids: RunId[]) {
  return requestClient.delete<boolean>('/dataforge/access/task/delete-list', {
    params: { ids },
  });
}

/** 查询表级同步结果分页 */
export function getAccessTaskDetailPage(
  params: DataforgeAccessApi.DetailPageReq,
) {
  return requestClient.get<PageResult<DataforgeAccessApi.AccessTaskDetail>>(
    '/dataforge/access/task-detail/page',
    { params },
  );
}
