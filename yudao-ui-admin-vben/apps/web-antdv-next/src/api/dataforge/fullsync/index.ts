import type { PageResult } from '@vben/request';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
  getDatabaseTableMeta as getTableMeta,
} from '#/api/dataforge/database';
import { requestClient } from '#/api/request';

export type Id = number | string;
export interface DatabaseOption {
  id: Id;
  name: string;
  type: string;
  databaseName?: string;
}
export interface DatabaseTable {
  name: string;
  comment?: string;
}
export interface ResponsibleUser {
  id: Id;
  nickname: string;
  mobile?: string;
}
export interface NameMapping {
  fromPattern: string;
  toValue: string;
}
export interface ColumnMapping {
  source: string;
  target: string;
  type?: string;
  description?: string;
}
export interface FullSyncAppendColumn {
  name: string;
  type: string;
  description?: string;
  length?: number;
  scale?: number;
  defaultValue?: string;
  value?: string;
}
export interface FullSyncTableProperties {
  sourceComment?: string;
  targetTable?: string;
  targetComment?: string;
  partitionRule?: string;
  bucketRule?: string;
  replicaCount?: number;
  columnMappings?: ColumnMapping[];
  [key: string]: unknown;
}
export interface TableMapping {
  targetTableName?: string;
  targetTableComment?: string;
  regexTableMapper?: NameMapping[];
  regexColumnMapper?: NameMapping[];
  sourcePrimaryKeys?: string[];
  timestampColumn?: string;
  increaseColumnName?: string;
}
export interface FullSyncConfig {
  taskName: string;
  description?: string;
  remark?: string;
  sourceDatabaseId?: Id;
  targetDatabaseId?: Id;
  sourceSelectedTables: string[];
  taskType: number;
  sourceType?: number;
  engineType?: number;
  accessMode?: number;
  includeOrExclude?: number;
  executionMode?: string;
  mapperType?: 'ALL' | 'SINGLE';
  tableNameMapper?: NameMapping[];
  columnNameMapper?: NameMapping[];
  configMap?: Record<string, TableMapping>;
  tableProperties?: Record<string, FullSyncTableProperties>;
  appendColumns?: FullSyncAppendColumn[];
  responsibleUserId?: Id;
  responsibleName?: string;
  responsiblePhone?: string;
  taskStatus?: 0 | 1;
  scheduleEnabled?: boolean;
  executionMethod?: 'CRON' | 'MANUAL';
  cron?: string;
  errorContinue?: boolean;
  dirtyDataStrategy?: 'CONTINUE' | 'IGNORE' | 'STOP';
  engine?: 'DATAX' | 'FLINK' | 'SPARK';
  executionStrategy?: string;
  taskPriority?: 'HIGH' | 'HIGHEST' | 'LOW' | 'LOWEST' | 'MEDIUM';
  workerGroup?: string;
  retryTimes?: number;
  retryInterval?: number;
  delayTime?: number;
  driverCores?: number;
  driverMemory?: string;
  executorCount?: number;
  executorMemory?: string;
  executorCores?: number;
  yarnQueue?: string;
  partitionRule?: string;
  bucketRule?: string;
  replicaCount?: number;
  writeMode?: string;
  targetOnlyCreate?: boolean;
  targetSyncExit?: boolean;
  targetDropTable?: boolean;
  targetTruncateData?: boolean;
  [key: string]: unknown;
}
export interface Wizard {
  id: Id;
  revision: Id;
  currentStep: number;
  status: 'DRAFT' | 'FAILED' | 'SUBMITTED' | 'SUBMITTING' | 'UNKNOWN';
  config: FullSyncConfig;
  creator?: string;
  createTime?: number;
  updateTime?: number;
}
export interface WizardPageParams {
  pageNo: number;
  pageSize: number;
  taskName?: string;
  sourceDatabaseId?: Id;
  targetDatabaseId?: Id;
}
const base = '/dataforge/full-sync/wizard';
export const LIST_PATH = '/develop/integrate/full-sync';
export function createDefaultConfig(): FullSyncConfig {
  return {
    taskName: '',
    description: '',
    sourceSelectedTables: [],
    taskType: 2,
    sourceType: 1,
    engineType: 1,
    accessMode: 2,
    includeOrExclude: 1,
    executionMode: 'DBSWITCH',
    mapperType: 'SINGLE',
    configMap: {},
    tableProperties: {},
    appendColumns: [],
    taskStatus: 0,
    scheduleEnabled: false,
    executionMethod: 'MANUAL',
    cron: '',
    errorContinue: true,
    dirtyDataStrategy: 'IGNORE',
    engine: 'SPARK',
    executionStrategy: 'PARALLEL',
    taskPriority: 'MEDIUM',
    workerGroup: 'default',
    retryTimes: 0,
    retryInterval: 1,
    delayTime: 0,
    driverCores: 1,
    driverMemory: '512m',
    executorCount: 1,
    executorMemory: '512m',
    executorCores: 1,
    yarnQueue: '',
    writeMode: 'CREATE_NEW',
  };
}
export function getWizardPage(params: WizardPageParams) {
  return requestClient.get<PageResult<Wizard>>(`${base}/page`, { params });
}
export function getWizard(id: Id) {
  return requestClient.get<Wizard>(`${base}/${encodeURIComponent(String(id))}`);
}
export function createWizard(config: FullSyncConfig) {
  return requestClient.post<Wizard>(base, { step: 1, config });
}
export function saveWizardStep(
  id: Id,
  step: number,
  config: FullSyncConfig,
  expectedRevision: Id,
) {
  return requestClient.put<Wizard>(
    `${base}/${encodeURIComponent(String(id))}/step`,
    { id, step, config, expectedRevision },
  );
}
export function deleteWizard(id: Id, expectedRevision: Id) {
  return requestClient.delete<boolean>(
    `${base}/${encodeURIComponent(String(id))}`,
    { params: { expectedRevision } },
  );
}
export async function getDatabaseOptions(): Promise<DatabaseOption[]> {
  const databases = await getDatabaseSimpleList();
  return databases.map((item) => ({
    id: item.id,
    name: item.name || String(item.id),
    type:
      item.databaseTypeName ||
      item.productId ||
      String(item.databaseType ?? '未知类型'),
    databaseName: item.databaseSchema,
  }));
}
export async function getDatabaseTables(id: Id): Promise<DatabaseTable[]> {
  const tables = await getDatabaseTableList(id);
  return tables.map((item) => ({
    name: item.tableName,
    comment: item.remarks,
  }));
}
export function getDatabaseTableMeta(id: Id, tableName: string) {
  return getTableMeta(id, tableName);
}
export function getResponsibleUsers() {
  return requestClient.get<ResponsibleUser[]>('/system/user/simple-list');
}
