import type { IntegrationApi as Api } from '#/api/dataforge/integration';

export const runOptions: { label: string; value: Api.RunStatus }[] = [
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' },
  { label: '空闲', value: 'IDLE' },
];
export const statusOptions = [
  { label: '未上线', value: 0 },
  { label: '已上线', value: 1 },
];
export const strategyOptions: { label: string; value: Api.Strategy }[] = [
  { label: '并行', value: 'PARALLEL' },
  { label: '串行等待', value: 'SERIAL_WAIT' },
  { label: '串行抛弃', value: 'SERIAL_DISCARD' },
  { label: '串行优先', value: 'SERIAL_PRIORITY' },
];
export const priorityOptions: { label: string; value: Api.Priority }[] = [
  { label: '高', value: 'HIGH' },
  { label: '最高', value: 'HIGHEST' },
  { label: '中等', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
  { label: '最低', value: 'LOWEST' },
];
export interface CategoryNode {
  key: string;
  title: string;
  value: string;
  children?: CategoryNode[];
  selectable: boolean;
  isLeaf?: boolean;
  disabled?: boolean;
}
export function categoryTree(
  categories: Api.Category[],
  selection = false,
): CategoryNode[] {
  const nodes = new Map(
    categories.map((item) => [
      String(item.id),
      {
        key: String(item.id),
        value: String(item.id),
        title: item.name,
        selectable: true,
        disabled: selection && item.validFlag === false,
      } as CategoryNode,
    ]),
  );
  const roots: CategoryNode[] = [];
  for (const item of categories) {
    const node = nodes.get(String(item.id))!;
    const parent = nodes.get(String(item.parentId));
    if (parent && parent !== node) (parent.children ??= []).push(node);
    else roots.push(node);
  }
  for (const node of nodes.values()) node.isLeaf = !node.children?.length;
  return roots;
}
export function defaultTask(mode: Api.Mode): Api.SaveTask {
  return {
    mode,
    categoryId: '',
    ownerId: '',
    name: '',
    status: 0,
    engine: mode === 'REALTIME' ? 'FLINK' : 'SPARK',
    scheduler: 'DOLPHIN_SCHEDULER',
    cronExpression: '0 0 0 * * ?',
    priority: 'HIGH',
    retryCount: 0,
    retryInterval: 0,
    delayMinutes: 0,
    executionStrategy: 'SERIAL_DISCARD',
    workerGroup: 'default',
    description: '',
    engineConfig: {
      jvmInitialMemory: 1024,
      jvmMaximumMemory: 2048,
      channel: 1,
      byteSpeed: 0,
      recordSpeed: 0,
      errorLimit: 0,
      driverCores: 1,
      driverMemory: '512m',
      executorCount: 1,
      executorMemory: '512m',
      executorCores: 1,
      yarnQueue: '',
      jobManagerMemory: '1G',
      taskManagerMemory: '2G',
      slots: 2,
      taskManagerCount: 2,
      parallelism: 2,
    },
  };
}
export function saveTask(task: Api.Task): Api.SaveTask {
  return {
    id: task.id,
    revision: task.revision,
    mode: task.mode,
    categoryId: String(task.categoryId),
    ownerId: task.ownerId === null ? '' : String(task.ownerId),
    name: task.name,
    status: task.status,
    engine: task.engine,
    engineConfig: { ...task.engineConfig },
    scheduler: task.scheduler,
    cronExpression: task.cronExpression,
    priority: task.priority,
    retryCount: task.retryCount,
    retryInterval: task.retryInterval,
    delayMinutes: task.delayMinutes,
    executionStrategy: task.executionStrategy,
    workerGroup: task.workerGroup,
    description: task.description,
  };
}
export function duration(seconds?: null | number) {
  if (seconds === undefined || seconds === null) return '-';
  if (seconds < 60) return `${seconds}s`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${hours ? `${hours}h ` : ''}${minutes}m ${seconds % 60}s`;
}
export function cronSummary(expression?: string) {
  if (!expression) return '未设置调度周期';
  if (expression === '0 0 0 1 1 ?')
    return '在00:00, 限每月 1 号, 仅于一月份 执行';
  if (expression === '0 0 0 1 * ?') return '在00:00, 限每月 1 号 执行';
  if (expression === '0 0 0 * * ?') return '每天 00:00 执行';
  return expression;
}

export function recentRunTime(value?: null | number, now = Date.now()) {
  if (value === undefined || value === null) return '-';
  const time = new Date(value);
  const current = new Date(now);
  if (Number.isNaN(time.getTime())) return '-';
  if (time.toDateString() === current.toDateString()) {
    return `${String(time.getHours()).padStart(2, '0')}:${String(time.getMinutes()).padStart(2, '0')}`;
  }
  const days = Math.max(1, Math.floor((now - value) / 86_400_000));
  if (days >= 365) return `${Math.floor(days / 365)}年前`;
  if (days >= 30) return `${Math.floor(days / 30)}月前`;
  return `${days}天前`;
}
export const labelOf = (
  items: { label: string; value: number | string }[],
  value?: number | string,
) => items.find((item) => item.value === value)?.label ?? '-';

export interface FlowNode {
  id: string;
  label: string;
  type: string;
  x: number;
  y: number;
}
export interface FlowEdge {
  source: string;
  target: string;
}
export function readFlow(json?: string): {
  edges: FlowEdge[];
  nodes: FlowNode[];
} {
  try {
    const value = JSON.parse(json || '{}') as {
      edges?: FlowEdge[];
      nodes?: FlowNode[];
    };
    const nodes = (Array.isArray(value.nodes) ? value.nodes : []).filter(
      (node) =>
        typeof node.id === 'string' &&
        typeof node.label === 'string' &&
        Number.isFinite(node.x) &&
        Number.isFinite(node.y),
    );
    if (nodes.length === 0) return { edges: [], nodes: [] };
    const minimumX = Math.min(...nodes.map((node) => node.x));
    const minimumY = Math.min(...nodes.map((node) => node.y));
    const keys = new Set(nodes.map((node) => node.id));
    return {
      nodes: nodes.map((node) => ({
        ...node,
        x: node.x - minimumX,
        y: node.y - minimumY,
      })),
      edges: (Array.isArray(value.edges) ? value.edges : []).filter(
        (edge) => keys.has(edge.source) && keys.has(edge.target),
      ),
    };
  } catch {
    return { edges: [], nodes: [] };
  }
}
