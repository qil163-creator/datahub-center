import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/** 数据血缘：后端 Long 编号按无损类型保留；时间为毫秒时间戳。 */
export namespace GovernanceLineageApi {
  export type Id = number | string;
  export type Direction = 'BOTH' | 'DOWNSTREAM' | 'UPSTREAM';
  export type NodeType = 'EXTERNAL' | 'TABLE';
  export type SourceType =
    | 'DATA_ACCESS'
    | 'DEV_TASK'
    | 'ETL_PIPELINE'
    | 'INTEGRATION_TASK';
  export type ReportStatus =
    | 'FAILED'
    | 'PARTIAL'
    | 'REMOVED'
    | 'SUCCESS'
    | 'UNSUPPORTED';
  export type TransformType = 'DIRECT' | 'INDIRECT';
  export type TransformSubtype =
    | 'AGGREGATION'
    | 'CONDITIONAL'
    | 'FILTER'
    | 'GROUP_BY'
    | 'IDENTITY'
    | 'JOIN'
    | 'SORT'
    | 'TRANSFORMATION'
    | 'WINDOW';

  /** 血缘节点：表或外部数据集。 */
  export interface Node {
    id: Id;
    nodeType: NodeType;
    datasourceId?: Id;
    schemaName?: string;
    tableName?: string;
    externalKind?: string;
    externalKey?: string;
    displayName?: string;
    metaTableId?: Id;
    businessName?: string;
  }

  /** 产生血缘的来源对象。 */
  export interface SourceBrief {
    id: Id;
    sourceType: SourceType;
    sourceId: Id;
    sourceName?: string;
    status?: ReportStatus;
  }

  export interface GraphNode extends Node {
    /** 相对根节点的层级：上游为负，下游为正。 */
    depth: number;
    upstreamExpandable?: boolean;
    downstreamExpandable?: boolean;
  }

  export interface GraphEdge {
    upstreamNodeId: Id;
    downstreamNodeId: Id;
    origins: string[];
    sources: SourceBrief[];
  }

  export interface Graph {
    rootNodeId: Id;
    nodes: GraphNode[];
    edges: GraphEdge[];
    truncated?: boolean;
  }

  export interface ColumnGraphNode {
    nodeId: Id;
    column: string;
    depth: number;
    upstreamExpandable?: boolean;
    downstreamExpandable?: boolean;
    node?: Node;
  }

  export interface ColumnGraphEdge {
    upstreamNodeId: Id;
    upstreamColumn: string;
    downstreamNodeId: Id;
    downstreamColumn: string;
    transformType: TransformType;
    transformSubtype: TransformSubtype;
    ambiguous?: boolean;
    masking?: boolean;
    sources: SourceBrief[];
  }

  export interface ColumnGraph {
    rootNodeId: Id;
    rootColumn: string;
    nodes: ColumnGraphNode[];
    edges: ColumnGraphEdge[];
    truncated?: boolean;
  }

  export interface ImpactItem {
    nodeId: Id;
    column?: string;
    depth: number;
    sources: SourceBrief[];
    node?: Node;
  }

  export interface Impact {
    items: ImpactItem[];
    truncated?: boolean;
  }

  export interface Issue {
    code: string;
    message?: string;
    statementIndex?: number;
  }

  export interface Source {
    id: Id;
    sourceType: SourceType;
    sourceId: Id;
    sourceName?: string;
    sourceRevision?: Id;
    analyzerVersion?: number;
    status: ReportStatus;
    issues?: Issue[];
    tableEdgeCount?: number;
    columnEdgeCount?: number;
    /** 毫秒时间戳。 */
    reportedTime?: number;
  }

  export interface SourcePageReq extends PageParam {
    sourceType?: SourceType;
    status?: ReportStatus;
    keyword?: string;
  }
}

/** 检索血缘节点。 */
export function searchLineageNodes(
  params: { datasourceId?: GovernanceLineageApi.Id; keyword?: string; limit?: number },
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceLineageApi.Node[]>(
    '/governance/lineage/node/search',
    { params, signal },
  );
}

/** 按元数据表编号定位血缘节点；尚无血缘时为 null。 */
export function resolveLineageNode(
  metaTableId: GovernanceLineageApi.Id,
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceLineageApi.Node | null>(
    '/governance/lineage/node/resolve',
    { params: { metaTableId }, signal },
  );
}

/** 节点在字段血缘中出现过的字段，与元数据字段合并。 */
export function getLineageNodeColumns(
  nodeId: GovernanceLineageApi.Id,
  signal?: AbortSignal,
) {
  return requestClient.get<string[]>('/governance/lineage/node/columns', {
    params: { nodeId },
    signal,
  });
}

/** 表级血缘图。 */
export function getLineageGraph(
  params: {
    depth: number;
    direction: GovernanceLineageApi.Direction;
    nodeId: GovernanceLineageApi.Id;
  },
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceLineageApi.Graph>(
    '/governance/lineage/graph',
    { params, signal },
  );
}

/** 字段级血缘图。 */
export function getLineageColumnGraph(
  params: {
    column: string;
    depth: number;
    direction: GovernanceLineageApi.Direction;
    nodeId: GovernanceLineageApi.Id;
  },
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceLineageApi.ColumnGraph>(
    '/governance/lineage/column-graph',
    { params, signal },
  );
}

/** 下游影响分析。 */
export function getLineageImpact(
  params: { column?: string; depth?: number; nodeId: GovernanceLineageApi.Id },
  signal?: AbortSignal,
) {
  return requestClient.get<GovernanceLineageApi.Impact>(
    '/governance/lineage/impact',
    { params, signal },
  );
}

/** 血缘解析记录分页。 */
export function getLineageSourcePage(params: GovernanceLineageApi.SourcePageReq) {
  return requestClient.get<PageResult<GovernanceLineageApi.Source>>(
    '/governance/lineage/source/page',
    { params },
  );
}
