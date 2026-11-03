import { requestClient } from '#/api/request';

export namespace FoundationDwLayerApi {
  export type Id = number | string;
  /** 数仓分层信息 */
  export interface DwLayer {
    id: Id; // 主键
    parentId?: Id; // 父级ID，0表示一级分类节点
    ancestors?: string; // 祖级列表，如 0 或 0,1
    levelNo?: number; // 层级：1分类节点，2实际分层
    layerName?: string; // 分层名称
    layerCode?: string; // 分层编码：ODS/DIM/DWD/DWS/ADS，分类节点为空
    nodeType?: string; // 节点类型：CATEGORY分类节点/LAYER实际分层节点
    tablePrefix?: string; // 默认表前缀，仅实际分层节点填写
    defaultTableType?: string; // 默认表类型：SOURCE贴源/DETAIL明细/DIMENSION维度/SUMMARY汇总/APPLICATION应用
    ownerUserId: Id; // 负责人ID
    ownerName?: null | string; // 负责人名称
    logicModelCount?: null | number | string; // 当前分层关联的逻辑模型数
    logicModelCountAvailable?: boolean; // 是否有权查看关联模型统计
    sortOrder?: number; // 排序
    isBuiltin?: number; // 是否内置：0否 1是，内置节点不允许删除
    status?: number; // 状态：0启用 1停用
    description: string; // 说明
    remark: string; // 备注
    createTime?: number; // 创建时间，毫秒时间戳
    children?: DwLayer[];
  }

  export interface ListReq {
    layerName?: string;
    isBuiltin?: number;
    status?: number;
  }

  /** 前缀变更预览中的单个物理模型 */
  export interface PrefixPreviewItem {
    modelId: Id;
    modelName?: string;
    code?: string;
    /** 设计状态：DRAFT/PUBLISHED/DISCARDED */
    status?: string;
    /** 部署状态：NOT_DEPLOYED/RUNNING/SUCCEEDED/FAILED/UNKNOWN */
    deployStatus?: string;
    createMode?: string;
    currentTableName?: string;
    /** 按候选前缀推导的表名；为空表示无法推导或不适用 */
    candidateTableName?: null | string;
    changed: boolean;
    note?: string;
  }

  /**
   * 前缀变更预览（只读）。
   * blocked 表示该分层已被模型引用，当前策略不允许修改前缀；
   * 预览仅展示候选表名差异，不会自动重命名已发布物理表。
   */
  export interface PrefixPreview {
    layerId: Id;
    layerCode?: string;
    layerName?: string;
    nodeType?: string;
    applicable: boolean;
    reason?: string;
    currentPrefix?: string;
    candidatePrefix?: string;
    prefixChanged: boolean;
    blocked: boolean;
    logicCount: number;
    physicalCount: number;
    /** 为 true 时 items 仅包含前 100 个物理模型 */
    truncated: boolean;
    items: PrefixPreviewItem[];
  }
}

/** 查询数仓分层列表 */
export function getDwLayerList(params: FoundationDwLayerApi.ListReq = {}) {
  return requestClient.get<FoundationDwLayerApi.DwLayer[]>(
    '/modeling/dwlayer/list',
    { params },
  );
}

/** 查询数仓分层详情 */
export function getDwLayer(id: FoundationDwLayerApi.Id) {
  return requestClient.get<FoundationDwLayerApi.DwLayer>(
    '/modeling/dwlayer/get',
    { params: { id } },
  );
}

/** 新增数仓分层 */
export function createDwLayer(data: FoundationDwLayerApi.DwLayer) {
  return requestClient.post('/modeling/dwlayer/create', data);
}

/** 修改数仓分层 */
export function updateDwLayer(data: FoundationDwLayerApi.DwLayer) {
  return requestClient.put('/modeling/dwlayer/update', data);
}

/** 修改数仓分层状态 */
export function updateDwLayerStatus(
  id: FoundationDwLayerApi.Id,
  status: number,
) {
  return requestClient.put('/modeling/dwlayer/update-status', {
    id,
    status,
  });
}

/** 前缀变更影响预览：候选表名与差异，只读，不会重命名已发布物理表 */
export function getDwLayerPrefixPreview(
  id: FoundationDwLayerApi.Id,
  tablePrefix: string,
) {
  return requestClient.get<FoundationDwLayerApi.PrefixPreview>(
    '/modeling/dwlayer/prefix-preview',
    { params: { id, tablePrefix } },
  );
}

/** 删除数仓分层 */
export function deleteDwLayer(id: FoundationDwLayerApi.Id) {
  return requestClient.delete(`/modeling/dwlayer/delete?id=${id}`);
}

/** 导出数仓分层 */
export function exportDwLayer(params: any) {
  return requestClient.download('/modeling/dwlayer/export-excel', {
    params,
  });
}
