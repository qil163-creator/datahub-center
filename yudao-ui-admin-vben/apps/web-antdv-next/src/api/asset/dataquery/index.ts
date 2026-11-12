import { consumptionHeaders } from '#/api/asset/consumption';
import { requestClient } from '#/api/request';

export namespace DataQueryApi {
  /** 可查询的数据源 */
  export interface Datasource {
    id: number;
    name: string;
    usageType?: number;
    productType?: string;
    /** 数据库类型图标路径（数据库类型页维护） */
    iconPath?: string;
  }

  /** 表字段 */
  export interface Column {
    columnName: string;
    typeName?: string;
  }

  /** 查询结果列 */
  export interface ResultColumn {
    name: string;
    label: string;
    typeName?: string;
  }

  /** 查询结果 */
  export interface Result {
    columns?: ResultColumn[];
    rows?: Record<string, unknown>[];
    truncated?: boolean;
    elapsedMs?: number;
    /** 被动态脱敏的列（原值不在 rows 中）；为空表示没有列被脱敏 */
    maskedColumns?: { categoryNames?: string[]; name: string; reason?: string }[];
  }

  /** 执行查询请求 */
  export interface ExecuteParams {
    datasourceId: number;
    sql: string;
    limit?: number;
  }

  /** 文件目录节点：1-目录 2-SQL 脚本 */
  export const FILE_NODE_FOLDER = 1;
  export const FILE_NODE_FILE = 2;
  /** 可见范围：1-公开 2-私有 */
  export const FILE_VISIBILITY_PUBLIC = 1;
  export const FILE_VISIBILITY_PRIVATE = 2;

  export interface QueryFile {
    id?: number;
    parentId?: number;
    nodeType: number;
    name: string;
    sqlText?: string;
    datasourceId?: number;
    queryLimit?: number;
    visibility?: number;
    owned?: boolean;
    sort?: number;
    remark?: string;
    children?: QueryFile[];
  }
}

/** 获得可查询的数据源列表 */
export function getDataQueryDatasourceList() {
  return requestClient.get<DataQueryApi.Datasource[]>(
    '/asset/data-query/datasource-list',
  );
}

/** 获得数据源下的表名列表 */
export function getDataQueryTableList(datasourceId: number) {
  return requestClient.get<string[]>('/asset/data-query/table-list', {
    params: { datasourceId },
  });
}

/** 获得数据源下指定表的字段列表 */
export function getDataQueryColumnList(
  datasourceId: number,
  tableName: string,
) {
  return requestClient.get<DataQueryApi.Column[]>(
    '/asset/data-query/column-list',
    { params: { datasourceId, tableName } },
  );
}

/** 只读执行单条 SELECT 查询 */
export function executeDataQuery(
  data: DataQueryApi.ExecuteParams,
  consumptionKey?: string,
) {
  return requestClient.post<DataQueryApi.Result>(
    '/asset/data-query/execute',
    data,
    { headers: consumptionHeaders(consumptionKey) },
  );
}

/** 获得查询文件目录扁平列表 */
export function getDataQueryFileList() {
  return requestClient.get<DataQueryApi.QueryFile[]>(
    '/asset/data-query/file/list',
  );
}

/** 获得查询脚本详情（含 SQL） */
export function getDataQueryFile(id: number) {
  return requestClient.get<DataQueryApi.QueryFile>(
    `/asset/data-query/file/get?id=${id}`,
  );
}

/** 创建目录或 SQL 脚本 */
export function createDataQueryFile(data: DataQueryApi.QueryFile) {
  return requestClient.post<number>('/asset/data-query/file/create', data);
}

/** 更新目录或 SQL 脚本 */
export function updateDataQueryFile(data: DataQueryApi.QueryFile) {
  return requestClient.put('/asset/data-query/file/update', data);
}

/** 删除目录或 SQL 脚本 */
export function deleteDataQueryFile(id: number) {
  return requestClient.delete(`/asset/data-query/file/delete?id=${id}`);
}
