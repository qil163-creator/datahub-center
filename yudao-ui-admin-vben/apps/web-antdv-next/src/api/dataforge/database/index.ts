import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace FoundationDatabaseApi {
  export type DiagnosisStatus = 'FAIL' | 'PASS' | 'SKIPPED' | 'WARN';
  export interface DiagnosisCounts {
    normalCount: number;
    warningCount: number;
    errorCount: number;
    skippedCount: number;
    totalCount: number;
  }
  export interface DiagnosisItem {
    checkCode: string;
    checkName: string;
    checkContent: string;
    status: DiagnosisStatus;
    message: string;
    impact: string;
    durationMs: number;
  }
  export interface DiagnosisGroup extends DiagnosisCounts {
    groupCode: string;
    groupName: string;
    description: string;
    status: DiagnosisStatus;
    items: DiagnosisItem[];
  }
  export interface Diagnosis extends DiagnosisCounts {
    diagnosisId: string;
    overallStatus: DiagnosisStatus;
    connectionSuccessful: boolean;
    checkedAt: number;
    durationMs: number;
    groups: DiagnosisGroup[];
  }
  export type DiagnosisReq = (
    | { connection: Database; databaseId?: never }
    | { connection?: never; databaseId: Id }
  ) & { includeWriteChecks?: boolean };
  /** 数据连接信息 */
  export interface Database {
    id: Id; // 主键id
    name?: string; // 连接名称
    databaseType?: Id; // 连接类型编号
    databaseIp?: string; // 主机ip地址
    databasePort?: string; // 端口
    databaseName?: string; // 库名
    databaseSchema?: string; // schema
    status?: number; // 状态
    connectionStatus?: number; // 连通状态：0未检测 1在线 2离线
    lastTestTime?: number; // 最近一次连接测试时间（毫秒时间戳）
    lastTestMsg?: string; // 最近一次连接测试结果说明
    usageType?: number; // 用途分类：1-业务库 2-中台库
    userName: string; // 用户名
    password?: null | string; // 详情不返回密码，编辑时空值保留原凭据
    hasPassword?: boolean; // 只读标记，用于修改目标时重新输入凭据的提示
    jdbcUrl?: string; // URL
    orgId?: Id; // 单位id
    extendConfig?: string; // URL 参数
    sort?: number; // 排序
    remark?: string; // 备注
    creatorName?: string; // 创建者
    updaterName?: string; // 更新者
    typeCode?: string; // 数据源编码，用于判断是否具备独立 Schema
    productId?: string;
    capabilities?: Record<
      string,
      {
        reason: string;
        status: 'DECLARED' | 'UNSUPPORTED' | 'UNVERIFIED' | 'VERIFIED';
      }
    >;
    createTime?: number; // 创建时间（毫秒时间戳）
    updateTime?: number; // 修改时间（毫秒时间戳）
    databaseTypeName?: string; // 连接类型名称
    iconPath?: string; // 连接图标路径
    orgName?: string; // 单位名称
  }

  /** 数据源精简信息 */
  export interface DatabaseSimple {
    id: number | string;
    name?: string;
    usageType?: number;
    databaseType?: Id;
    databaseTypeName?: string;
    productId?: string;
    /** 源产品在组件中声明的游标目标；执行前仍须验证驱动、结构和键语义。 */
    cursorTargets?: readonly string[];
    databaseSchema?: string;
  }

  /** 数据源下的表信息 */
  export interface DatabaseTable {
    tableName: string;
    remarks?: string;
    viewTable?: boolean;
  }

  export type Id = number | string;

  /** 数据源表字段结构 */
  export interface DatabaseTableColumn {
    fieldName: string;
    fieldTypeName?: string;
    remarks?: string;
    nullable?: boolean;
    pk?: boolean;
    autoIncrement?: boolean;
    length?: number;
    precision?: number;
    scale?: number;
    defaultValue?: string;
  }

  /** 数据源表属性 */
  export interface DatabaseTableMeta {
    schemaName?: string;
    tableName: string;
    tableType?: string;
    remarks?: string;
    primaryKeys?: string[];
    createSql?: string;
    columns?: DatabaseTableColumn[];
  }

  export interface DatabasePreviewColumn {
    name: string;
    label?: string;
    typeName?: string;
  }

  export interface DatabasePreview {
    columns?: DatabasePreviewColumn[];
    rows?: Record<string, unknown>[];
    truncated?: boolean;
    /** 被动态脱敏的列（原值不在 rows 中）；为空表示没有列被脱敏 */
    maskedColumns?: { categoryNames?: string[]; name: string; reason?: string }[];
  }

  export interface DatabasePreviewReq {
    databaseId: Id;
    tableName: string;
    limit?: number;
  }
}

/** 查询连接信息分页 */
export function getDatabasePage(params: PageParam) {
  return requestClient.get<PageResult<FoundationDatabaseApi.Database>>(
    '/dataforge/database/page',
    { params },
  );
}

/** 查询连接信息详情 */
export function getDatabase(id: FoundationDatabaseApi.Id) {
  return requestClient.get<FoundationDatabaseApi.Database>(
    '/dataforge/database/get',
    { params: { id } },
  );
}

/** 测试数据库连接 */
export function testDatabase(id: FoundationDatabaseApi.Id) {
  return requestClient.get<boolean>('/dataforge/database/test', {
    params: { id },
  });
}

/** 按表单内容测试数据库连接 */
export function testDatabaseConnection(data: FoundationDatabaseApi.Database) {
  return requestClient.post<boolean>(
    '/dataforge/database/test-connection',
    databaseRequest(data),
  );
}

/** 分组诊断；默认只读，连接参数只留在当前请求内存中。 */
export function diagnoseDatabase(
  data: FoundationDatabaseApi.DiagnosisReq,
  signal?: AbortSignal,
) {
  return requestClient.post<FoundationDatabaseApi.Diagnosis>(
    '/dataforge/database/diagnose',
    data.connection
      ? { ...data, connection: databaseRequest(data.connection) }
      : data,
    { signal, timeout: 60_000 },
  );
}

/** 新增连接信息 */
export function createDatabase(data: FoundationDatabaseApi.Database) {
  return requestClient.post<FoundationDatabaseApi.Id>(
    '/dataforge/database/create',
    databaseRequest(data),
  );
}

/** 修改连接信息 */
export function updateDatabase(data: FoundationDatabaseApi.Database) {
  return requestClient.put<boolean>(
    '/dataforge/database/update',
    databaseRequest(data),
  );
}

/** 只读凭据标记不可作为客户端保存或测试的输入。 */
function databaseRequest(data: FoundationDatabaseApi.Database) {
  const request = { ...data };
  delete request.hasPassword;
  return request;
}

/** 删除连接信息 */
export function deleteDatabase(id: FoundationDatabaseApi.Id) {
  return requestClient.delete<boolean>('/dataforge/database/delete', {
    params: { id },
  });
}

/** 批量删除连接信息 */
export function deleteDatabaseList(ids: FoundationDatabaseApi.Id[]) {
  return requestClient.delete<boolean>('/dataforge/database/delete-list', {
    params: { ids },
  });
}

/** 导出连接信息 */
export function exportDatabase(params: any) {
  return requestClient.download('/dataforge/database/export-excel', {
    params,
  });
}

/** 查询连接信息精简列表（可按用途分类过滤：1-业务库 2-中台库） */
export function getDatabaseSimpleList(usageType?: number) {
  return requestClient.get<FoundationDatabaseApi.DatabaseSimple[]>(
    '/dataforge/database/simple-list',
    {
      params:
        usageType === null || usageType === undefined ? {} : { usageType },
    },
  );
}

/** 查询数据源下的表清单 */
export function getDatabaseTableList(
  id: number | string,
  options?: { silentError?: boolean },
) {
  return requestClient.get<FoundationDatabaseApi.DatabaseTable[]>(
    `/dataforge/database/table-list?id=${id}`,
    { silentError: options?.silentError },
  );
}

/** 查询数据源下指定表的属性与字段结构 */
export function getDatabaseTableMeta(
  id: FoundationDatabaseApi.Id,
  tableName: string,
  options?: { silentError?: boolean },
) {
  return requestClient.get<FoundationDatabaseApi.DatabaseTableMeta>(
    '/dataforge/database/table-meta',
    { params: { id, tableName }, silentError: options?.silentError },
  );
}

/** 只读预览数据源表样例数据 */
export function previewDatabaseTable(
  data: FoundationDatabaseApi.DatabasePreviewReq,
  options?: { silentError?: boolean },
) {
  return requestClient.post<FoundationDatabaseApi.DatabasePreview>(
    '/dataforge/database/preview-table',
    data,
    { silentError: options?.silentError },
  );
}
