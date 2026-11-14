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
    /** 合并策略：REPLACE_SCOPE-范围替换（默认） APPEND-追加 UPSERT-更新插入 */
    mergeStrategy?: string;
    /** UPSERT 业务键字段码，英文逗号分隔 */
    businessKeyFields?: string;
    /** 录入页面冻结的前几列（0 表示不冻结） */
    freezeColumnCount?: number;
    /** 多级汇总方式：DETAIL-明细汇集（默认） GROUP-分组合计 */
    rollupMode?: string;
    /** 分组合计的分组字段码，英文逗号分隔 */
    rollupGroupFields?: string;
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
    minValue?: string; // 最小值（数值/日期时间，字符串保持精度）
    maxValue?: string; // 最大值
    regexPattern?: string; // 格式正则（字符串字段，整体匹配）
    regexMessage?: string; // 格式不符提示语
    uniqueFlag?: number; // 同一次填报内取值唯一：0-否 1-是
    ruleSeverity?: number; // 规则级别：1-错误（阻断提交） 2-警告
    dictType?: string; // 选择值来源的系统字典类型
    /** 行内公式；设置后为只读计算列，提交时由服务端重算 */
    formulaExpr?: string;
    /** 录入列宽（像素） */
    columnWidth?: number;
    /** 表头分组：相邻同名字段合并为上一级表头 */
    headerGroup?: string;
    /** 数据表下拉：来源数据源（发布时读取取值固化） */
    refDatabaseId?: null | number | string;
    refTableName?: string;
    refValueColumn?: string;
    /** 级联：上级字段码与来源表中的上级取值列 */
    refParentField?: string;
    refParentColumn?: string;
    /** 人员/部门选择范围：ORG-报送组织及下级 ALL-全部 */
    refScope?: string;
    /** 分组合计的计算方式：SUM MAX MIN COUNT FIRST，空表示不输出 */
    rollupFunc?: string;
  }

  /** 填报表校验规则（行级 / 表级 / 跨期） */
  export interface FillTableRule {
    id?: number;
    tableId?: number;
    name?: string;
    /** ROW-行级 TABLE-表级（汇总） PERIOD-跨期（与上期比较） */
    scope?: 'PERIOD' | 'ROW' | 'TABLE';
    /** 规则表达式：结果为假即违反，空值视为通过 */
    expression?: string;
    message?: string;
    /** 1-错误（阻断提交） 2-警告 */
    severity?: number;
    /** 问题定位字段码 */
    fieldCode?: string;
    sort?: number;
    /** 0-停用 1-启用 */
    enabled?: number;
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

/** 查询填报表校验规则 */
export function getFillTableRuleList(tableId: number | string) {
  return requestClient.get<DataforgeFillTableApi.FillTableRule[]>(
    `/dataforge/fill-table-rule/list-by-table?tableId=${tableId}`,
  );
}

/** 新增填报表校验规则 */
export function createFillTableRule(data: DataforgeFillTableApi.FillTableRule) {
  return requestClient.post<number>('/dataforge/fill-table-rule/create', data);
}

/** 修改填报表校验规则 */
export function updateFillTableRule(data: DataforgeFillTableApi.FillTableRule) {
  return requestClient.put('/dataforge/fill-table-rule/update', data);
}

/** 删除填报表校验规则 */
export function deleteFillTableRule(id: number | string) {
  return requestClient.delete(`/dataforge/fill-table-rule/delete?id=${id}`);
}

/** 下载 Excel 模板（含必填标记与字典下拉） */
export function downloadFillDataTemplate(id: number) {
  return requestClient.download(
    `/dataforge/fill-table/data/import-template?id=${id}`,
  );
}
