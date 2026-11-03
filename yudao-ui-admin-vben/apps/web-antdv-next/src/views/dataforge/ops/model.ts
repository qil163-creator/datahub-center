import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import dagre from 'dagre';

import { readFlow } from '../integration/model';

export const statusOptions: { label: string; value: Api.Status }[] = [
  { label: '等待', value: 'WAITING' },
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' },
  { label: '空闲', value: 'IDLE' },
  { label: '停止', value: 'STOPPED' },
  { label: '未知', value: 'UNKNOWN' },
];
export const queueStateOptions: { label: string; value: Api.QueueState }[] = [
  { label: '排队中', value: 'QUEUED' },
  { label: '派发中', value: 'DISPATCHING' },
  { label: '已派发', value: 'ACTIVE' },
  { label: '停止中', value: 'STOP_REQUESTED' },
  { label: '已结束', value: 'DONE' },
  { label: '已抛弃', value: 'DISCARDED' },
  { label: '已被抢占', value: 'SUPERSEDED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '待确认', value: 'UNKNOWN' },
];
export const queueStateLabel = (state?: Api.QueueState) =>
  queueStateOptions.find((option) => option.value === state)?.label ?? state ?? '-';
const terminalQueueStates = new Set<Api.QueueState>([
  'CANCELLED', 'DISCARDED', 'DONE', 'REJECTED', 'SUPERSEDED',
]);
/** 队列终态独立于执行状态；未知结果继续观察，绝不在页面重投任务。 */
export function isActiveOpsInstance(record?: Pick<Api.Instance, 'queueState' | 'status'>) {
  if (!record) return false;
  if (record.queueState) return !terminalQueueStates.has(record.queueState);
  return ['RUNNING', 'UNKNOWN', 'WAITING'].includes(record.status);
}
export const kindLabels: Record<Api.Kind, { category: string; instance: string; title: string }> = {
  INTEGRATION: { category: '数据集成', instance: '任务实例', title: '数据集成实例' },
  DEVELOPMENT: { category: '数据开发', instance: '任务实例', title: '数据开发实例' },
  FULL_SYNC: { category: '整库同步', instance: '任务实例', title: '整库同步实例' },
  JOB: { category: '作业', instance: '作业实例', title: '作业管理实例' },
};
export const taskTypeOptions = [
  { label: '实时任务', value: 'REALTIME' },
  { label: '离线任务', value: 'OFFLINE' },
];
export function executionTypeLabel(type: Api.Instance['executionType']) {
  if (type === 'STOP') return '停止';
  if (type === 'SCHEDULE') return '定时调度';
  return '启动任务流';
}
/** 接口按原系统可见时间传输字符串，展示不经过浏览器时区转换。 */
export const displayDate = (value?: null | string, seconds = false) =>
  value ? value.replace('T', ' ').slice(0, seconds ? 19 : 16) : '-';
/** 源创建时间原始时区为上海，列表转换到浏览器时区；详情保留原系统本地时间。 */
export function displayCreated(
  value?: null | string,
  origin: Api.Origin = 'SOURCE',
  timeZone?: string,
) {
  const text = displayDate(value);
  if (!value || origin !== 'SOURCE') return text;
  const normalized = value.replace(' ', 'T');
  const date = new Date(
    /(?:Z|[+-]\d{2}:?\d{2})$/u.test(normalized)
      ? normalized
      : `${normalized}+08:00`,
  );
  if (!Number.isFinite(date.getTime())) return text;
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
}
export const statusLabel = (status: Api.Status) =>
  statusOptions.find((option) => option.value === status)?.label ?? status;
export const rowIdentity = (row: Api.Identity) =>
  `${row.kind}:${row.origin}:${String(row.id)}`;
export const requestIdentity = (row: Api.Identity & { logIdentity?: Api.Identity }): Api.Identity => ({
  id: row.logIdentity?.id ?? row.id,
  kind: row.logIdentity?.kind ?? row.kind,
  origin: row.logIdentity?.origin ?? row.origin,
});
/** 迁入演示子任务没有独立执行记录，日志沿用所属作业身份，保留可见子任务名称。 */
export function childLogRecord(parent: Api.Instance, child: Api.Instance): Api.Instance {
  return parent.kind === 'JOB' && parent.origin === 'SOURCE'
    ? { ...child, kind: 'JOB', logIdentity: requestIdentity(parent) }
    : child;
}
export interface PublicFlowNode {
  id: string;
  label: string;
  type: string;
  publicConfig?: { name: string; value: string }[];
  publicFields?: {
    description?: string;
    length?: string;
    name: string;
    primary?: boolean;
    scale?: string;
    type?: string;
  }[];
  publicMappings?: { source: string; target: string }[];
  publicRules?: {
    description?: string;
    dimension?: string;
    field: string;
    name: string;
    rule: string;
  }[];
}
/** 详情按连线从左到右分层，避免源编辑器位置影响实例监控布局。 */
export function layoutFlow(json?: string) {
  const flow = readFlow(json);
  if (flow.nodes.length === 0) return flow;
  const graph = new dagre.graphlib.Graph()
    .setGraph({ rankdir: 'LR', ranksep: 130, nodesep: 40 })
    .setDefaultEdgeLabel(() => ({}));
  for (const node of flow.nodes)
    graph.setNode(node.id, { width: 34, height: 64 });
  for (const edge of flow.edges) graph.setEdge(edge.source, edge.target);
  dagre.layout(graph);
  const nodes = flow.nodes.map((node) => ({
    ...node,
    x: graph.node(node.id).x - 17,
    y: graph.node(node.id).y - 32,
  }));
  const minimumX = Math.min(...nodes.map((node) => node.x));
  const minimumY = Math.min(...nodes.map((node) => node.y));
  return {
    ...flow,
    nodes: nodes.map((node) => ({
      ...node,
      x: node.x - minimumX,
      y: node.y - minimumY,
    })),
  };
}
export function formatDuration(seconds?: null | number) {
  if (seconds === undefined || seconds === null) return '-';
  const total = Math.max(0, Math.floor(seconds));
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return [
    ...(days ? [`${days}d`] : []),
    ...(hours ? [`${hours}h`] : []),
    ...(minutes ? [`${minutes}m`] : []),
    `${rest}s`,
  ].join(' ');
}
export interface CategoryNode {
  key: string;
  title: string;
  isLeaf: boolean;
  children?: CategoryNode[];
}
/** 按名称搜索时保留祖先路径，类目 ID 全程使用字符串。 */
export function buildCategoryTree(
  categories: Api.Category[],
  kind: Api.Kind,
  keyword = '',
): CategoryNode[] {
  const nodes = new Map<string, CategoryNode>(
    categories.map((item) => [
      String(item.id),
      { key: String(item.id), title: item.name, isLeaf: true },
    ]),
  );
  const roots: CategoryNode[] = [];
  for (const item of categories) {
    const node = nodes.get(String(item.id))!;
    const parent = nodes.get(String(item.parentId));
    if (parent && parent !== node) {
      (parent.children ??= []).push(node);
      parent.isLeaf = false;
    } else roots.push(node);
  }
  const title = `${kindLabels[kind].category}类目`;
  const tree =
    roots.length === 1 && roots[0]?.title === title
      ? roots
      : [{ key: '__root', title, isLeaf: false, children: roots }];
  if (!keyword.trim()) return tree;
  const query = keyword.trim().toLocaleLowerCase();
  const filter = (items: CategoryNode[]): CategoryNode[] =>
    items.flatMap((item) => {
      if (item.title.toLocaleLowerCase().includes(query)) return [item];
      const children = filter(item.children ?? []);
      return children.length > 0 ? [{ ...item, children }] : [];
    });
  return filter(tree);
}
export function logRows(message: string, keyword = '') {
  const query = keyword.toLocaleLowerCase();
  const entries: string[] = [];
  for (const line of message.split('\n')) {
    if (
      /^\s*(?:\[|【)?\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/u.test(line) ||
      entries.length === 0
    )
      entries.push(line);
    else entries[entries.length - 1] += `\n${line}`;
  }
  return entries
    .map((text, index) => ({ text, line: index + 1 }))
    .filter(
      (row) =>
        row.text && (!query || row.text.toLocaleLowerCase().includes(query)),
    );
}
/** 使用文本分片高亮，日志内容不会作为 HTML 执行。 */
export function highlightLog(
  text: string,
  keyword: string,
): { highlight: boolean; level?: boolean; text: string }[] {
  if (!keyword)
    return text
      .split(/(\b(?:INFO|ERROR|WARN|DEBUG)\b)/u)
      .filter(Boolean)
      .map((part) => ({
        text: part,
        highlight: false,
        level: /^(?:INFO|ERROR|WARN|DEBUG)$/u.test(part),
      }));
  const query = keyword.toLocaleLowerCase();
  const lower = text.toLocaleLowerCase();
  const result: { highlight: boolean; text: string }[] = [];
  let position = 0;
  while (position < text.length) {
    const index = lower.indexOf(query, position);
    if (index === -1) {
      result.push({ text: text.slice(position), highlight: false });
      break;
    }
    if (index > position)
      result.push({ text: text.slice(position, index), highlight: false });
    result.push({
      text: text.slice(index, index + keyword.length),
      highlight: true,
    });
    position = index + keyword.length;
  }
  return result;
}
