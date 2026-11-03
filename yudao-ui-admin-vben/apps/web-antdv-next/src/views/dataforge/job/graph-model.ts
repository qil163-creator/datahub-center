import { createsCycle } from '../etl/pipeline/graph-contract';

export interface JobNode {
  id: string;
  kind: 'DEVELOPMENT' | 'INTEGRATION';
  taskId: number | string;
  name: string;
  x: number;
  y: number;
  priority?: 'HIGH' | 'HIGHEST' | 'LOW' | 'LOWEST' | 'MEDIUM';
  description?: string;
  status?: number;
  taskType?: string;
  dbType?: string;
  [key: string]: unknown;
}

export interface JobEdge {
  source: string;
  target: string;
  [key: string]: unknown;
}

export interface JobFlow {
  nodes: JobNode[];
  edges: JobEdge[];
  [key: string]: unknown;
}

export function jobTaskIcon(task: {
  dbType?: string;
  kind: JobNode['kind'];
  taskType?: string;
}) {
  const type = task.taskType?.toUpperCase() || '';
  if (task.kind === 'DEVELOPMENT' && ['PROCEDURE', 'SQL'].includes(type)) {
    const database = task.dbType?.toLowerCase();
    if (database === 'mysql' || database === 'postgresql') return `/assets/qdata-fullsync/type-${database}.png`;
  }
  if (type.includes('FLINK')) return '/assets/qdata-integration/flink.svg';
  if (type.includes('SPARK')) return '/assets/qdata-integration/spark.svg';
  if (type === 'DATAX') return '/assets/qdata-integration/datax.png';
  return `/assets/qdata-integration/${task.kind === 'INTEGRATION' ? 'spark.svg' : 'dolphin.png'}`;
}

export function parseJobFlow(value?: null | string): JobFlow {
  if (!value?.trim()) return { nodes: [], edges: [] };
  const data: unknown = JSON.parse(value);
  if (!data || typeof data !== 'object')
    throw new TypeError('作业流程格式无效');
  const flow = data as Partial<JobFlow>;
  if (!Array.isArray(flow.nodes) || !Array.isArray(flow.edges)) {
    throw new TypeError('作业流程缺少节点或连线');
  }
  const ids = new Set<string>();
  const nodes = flow.nodes.map((node, index) => {
    if (
      !node ||
      typeof node !== 'object' ||
      !node.id ||
      !['DEVELOPMENT', 'INTEGRATION'].includes(node.kind) ||
      !['number', 'string'].includes(typeof node.taskId)
    ) {
      throw new Error('作业节点格式无效');
    }
    const id = String(node.id);
    if (ids.has(id)) throw new Error('作业节点编号重复');
    ids.add(id);
    return {
      ...node,
      id,
      name: String(node.name || node.taskId),
      x: Number.isFinite(node.x) ? node.x : (index % 3) * 400 + 40,
      y: Number.isFinite(node.y) ? node.y : Math.floor(index / 3) * 190 + 40,
    };
  });
  for (const edge of flow.edges) {
    if (!edge || !ids.has(edge.source) || !ids.has(edge.target)) {
      throw new Error('作业连线引用了不存在的节点');
    }
  }
  return { ...flow, nodes, edges: flow.edges } as JobFlow;
}

export function canConnectJobNodes(
  edges: JobEdge[],
  source: string,
  target: string,
) {
  return (
    source !== target &&
    !edges.some((edge) => edge.source === source && edge.target === target) &&
    !createsCycle(
      edges.map((edge) => ({
        sourceNodeId: edge.source,
        targetNodeId: edge.target,
      })),
      source,
      target,
    )
  );
}
