import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace ModelingLogicTableApi {
  /** 表类型：贴源/维度/事实/汇总/ADS 应用 */
  export type TableType =
    | 'APPLICATION'
    | 'DETAIL'
    | 'DIMENSION'
    | 'SOURCE'
    | 'SUMMARY';

  export interface PageReq extends PageParam {
    tableType: TableType;
    name?: string;
    sourceSystemId?: number | string;
    enabled?: number;
    contactUserId?: number | string;
    dataTimeliness?: string;
    businessType?: string;
    dataDomainId?: number | string;
    subjectDomainId?: number | string;
    sortField?: 'columnCount' | 'createTime' | 'id' | 'updateTime';
    asc?: boolean;
  }

  /** 逻辑表列表行 */
  export interface LogicTable {
    id: number | string;
    revision?: number | string;
    name?: string;
    code?: string;
    tableNameNotation?: string;
    tableType?: TableType;
    enabled?: number; // 0 停用，1 启用
    description?: string;
    columnCount?: number;
    sourceSystemId?: number | string;
    sourceSystemName?: string;
    dbType?: string;
    dbName?: string;
    dataDomainId?: number | string;
    dataDomainName?: string;
    dataDomainIcon?: string;
    subjectDomainId?: number | string;
    subjectDomainName?: string;
    entityId?: number | string;
    entityName?: string;
    objType?: string;
    businessType?: string;
    dataTimeliness?: string;
    statisticsStr?: string;
    contactUserId?: number | string;
    contactNumber?: string;
    ownerName?: string;
    ownerPhone?: string;
    creator?: string;
    creatorName?: string;
    createTime?: number | string;
    updateTime?: number | string;
  }

  /** 左侧类目树节点 */
  export interface CatNode {
    key: string;
    name: string;
    type: 'DATA_DOMAIN' | 'SOURCE_SYSTEM' | 'SUBJECT_DOMAIN';
    sourceSystemId?: number | string;
    dataDomainId?: number | string;
    subjectDomainId?: number | string;
    children?: CatNode[];
  }

  export interface DeleteCheck {
    canDeleteCount: number;
    cannotDeleteCount: number;
    canDeleteIds: Array<number | string>;
  }

  export interface SourceSystem {
    id: number | string;
    name: string;
  }
}

/** 逻辑表分页 */
export function getLogicTablePage(params: ModelingLogicTableApi.PageReq) {
  return requestClient.get<PageResult<ModelingLogicTableApi.LogicTable>>(
    '/modeling/logic-table/page',
    { params },
  );
}

/** 逻辑表类目树 */
export function getLogicTableCatTree(
  tableType: ModelingLogicTableApi.TableType,
) {
  return requestClient.get<ModelingLogicTableApi.CatNode[]>(
    '/modeling/logic-table/cat-tree',
    { params: { tableType } },
  );
}

/** 启用的来源系统 */
export function getLogicTableSourceSystems() {
  return requestClient.get<ModelingLogicTableApi.SourceSystem[]>(
    '/modeling/logic-table/source-system/list',
  );
}

/** 启用或停用逻辑表 */
export function updateLogicTableEnabled(id: number | string, enabled: number) {
  return requestClient.put<boolean>(
    '/modeling/logic-table/update-enabled',
    null,
    {
      params: { id, enabled },
    },
  );
}

/** 批量删除前检查 */
export function checkLogicTableDelete(ids: Array<number | string>) {
  return requestClient.get<ModelingLogicTableApi.DeleteCheck>(
    '/modeling/logic-table/delete-check',
    { params: { ids: ids.join(',') } },
  );
}

/** 删除逻辑表（支持批量） */
export function deleteLogicTables(ids: Array<number | string>) {
  return requestClient.delete<boolean>('/modeling/logic-table/delete', {
    params: { ids: ids.join(',') },
  });
}
