import { requestClient } from '#/api/request';

export namespace GovernanceMetaImportApi {
  /** 数据字典导入结果 */
  export interface ImportResult {
    instanceId?: number;
    dbCount?: number;
    tableCount?: number;
    columnCount?: number;
    tableAdd?: number;
    tableUpdate?: number;
    columnAdd?: number;
    columnUpdate?: number;
    columnDelete?: number;
    invalidRows?: number;
    errors?: string[];
  }

  /** 手工登记字段 */
  export interface RegisterColumn {
    columnName?: string;
    columnComment?: string;
    dataType?: string;
    dataLength?: number;
    dataPrecision?: number;
    dataScale?: number;
    nullableFlag?: number;
    defaultValue?: string;
    pkFlag?: number;
    autoIncrementFlag?: number;
  }

  /** 手工登记表 */
  export interface RegisterTable {
    datasourceId?: number;
    sourceSystemId?: number;
    sourceSystemName?: string;
    dbName?: string;
    tableName?: string;
    tableComment?: string;
    columns?: RegisterColumn[];
  }
}

/** 导入 Excel 数据字典 */
export function importMetaDict(
  datasourceId: number | string,
  file: File,
  sourceSystemId?: number,
  sourceSystemName?: string,
) {
  return requestClient.upload<GovernanceMetaImportApi.ImportResult>(
    '/governance/meta-import/excel',
    { datasourceId, sourceSystemId, sourceSystemName, file },
  );
}

/** 下载数据字典导入模板 */
export function downloadMetaImportTemplate() {
  return requestClient.download('/governance/meta-import/template');
}

/** 手工登记单张表 */
export function registerMetaTable(data: GovernanceMetaImportApi.RegisterTable) {
  return requestClient.post<number>(
    '/governance/meta-import/register-table',
    data,
  );
}
