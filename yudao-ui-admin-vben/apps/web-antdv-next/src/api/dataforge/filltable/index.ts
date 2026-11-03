import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace DataforgeFillTableApi {
  /** 填报表定义 */
  export interface FillTable {
    id?: number;
    tableEngName?: string; // 物理表英文名
    tableCnName?: string; // 表中文名
    description?: string; // 描述
    databaseId?: number; // 目标数据源id
    databaseName?: string; // 目标数据源名称
    status?: number; // 发布状态：0-未发布 1-已发布
    releaseUserName?: string; // 发布人
    releaseTime?: string; // 发布时间
    orgId?: number; // 单位id
    orgName?: string; // 单位名称
    createTime?: string;
  }

  /** 填报表字段 */
  export interface FillTableColumn {
    id?: number;
    tableId?: number;
    name?: string; // 物理字段名
    fieldCode?: string; // 永久字段码（发布版本后不可变）
    cnName?: string; // 中文名称
    commentStr?: string; // 注释
    fieldType?: number; // 1-小数 2-字符串 3-日期 5-整数 6-长整型 9-日期时间 10-时间
    fillType?: number; // 1-手工录入 2-选择
    fillValues?: string; // 选择值（逗号分隔）
    fillHide?: number; // 录入是否隐藏
    fieldLength?: number; // 长度
    fieldScale?: number; // 小数位
    nullable?: number; // 是否可空
    ifQuery?: number; // 是否查询条件
    pk?: number; // 是否主键
    pkType?: number; // 1-UUID 2-自行录入
    sort?: number; // 顺序
  }
}

/** 查询填报表分页 */
export function getFillTablePage(params: PageParam) {
  return requestClient.get<PageResult<DataforgeFillTableApi.FillTable>>(
    '/dataforge/fill-table/page',
    { params },
  );
}

/** 查询填报表详情 */
export function getFillTable(id: number | string) {
  return requestClient.get<DataforgeFillTableApi.FillTable>(
    '/dataforge/fill-table/get',
    { params: { id } },
  );
}

/** 新增填报表 */
export function createFillTable(data: DataforgeFillTableApi.FillTable) {
  return requestClient.post('/dataforge/fill-table/create', data);
}

/** 修改填报表 */
export function updateFillTable(data: DataforgeFillTableApi.FillTable) {
  return requestClient.put('/dataforge/fill-table/update', data);
}

/** 删除填报表 */
export function deleteFillTable(id: number) {
  return requestClient.delete(`/dataforge/fill-table/delete?id=${id}`);
}

/** 发布填报表（创建物理表） */
export function releaseFillTable(id: number) {
  return requestClient.put(`/dataforge/fill-table/release?id=${id}`);
}

/** 取消发布（仅停用模板，不删除物理表） */
export function cancelFillTable(id: number) {
  return requestClient.put(`/dataforge/fill-table/cancel?id=${id}`);
}

/** 查询填报表字段列表 */
export function getFillTableColumnList(tableId: number) {
  return requestClient.get<DataforgeFillTableApi.FillTableColumn[]>(
    `/dataforge/fill-table-column/list-by-table?tableId=${tableId}`,
  );
}

/** 新增填报表字段 */
export function createFillTableColumn(
  data: DataforgeFillTableApi.FillTableColumn,
) {
  return requestClient.post('/dataforge/fill-table-column/create', data);
}

/** 修改填报表字段 */
export function updateFillTableColumn(
  data: DataforgeFillTableApi.FillTableColumn,
) {
  return requestClient.put('/dataforge/fill-table-column/update', data);
}

/** 删除填报表字段 */
export function deleteFillTableColumn(id: number) {
  return requestClient.delete(`/dataforge/fill-table-column/delete?id=${id}`);
}

/** 下载 Excel 模板（含必填标记与字典下拉） */
export function downloadFillDataTemplate(id: number) {
  return requestClient.download(
    `/dataforge/fill-table/data/import-template?id=${id}`,
  );
}
