import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { formatDurationMs } from './node-run-status';

type RuntimeMetrics = DataforgeEtlApi.RuntimeMetrics;

/** 上游累计等待占已运行时间的比例，达到该值视为明显背压 */
const HIGH_PRESSURE_RATIO = 0.5;
const MEDIUM_PRESSURE_RATIO = 0.2;
/** 运行中的队列填充率阈值；流程结束后队列已排空，不再参与判断 */
const HIGH_FILL_RATIO = 0.9;
const MEDIUM_FILL_RATIO = 0.6;

export type PressureLevel = 'high' | 'medium' | 'normal';

export const PRESSURE_COLORS: Record<PressureLevel, string> = {
  high: '#dc2626',
  medium: '#d97706',
  normal: '#64748b',
};

export interface RuntimeEdgeSummary {
  key: string;
  sourceNo: string;
  targetNo: string;
  sourceName: string;
  targetName: string;
  queued: number;
  capacity: number;
  delivered: number | string;
  blockedMillis: number;
  starvedMillis: number;
  /** 等待占已运行时间的比例；尚无运行时长时为 null */
  blockedRatio: null | number;
  starvedRatio: null | number;
  level: PressureLevel;
}

export interface ThroughputPoint {
  elapsedMillis: number;
  rowsPerSecond: number;
}

/** 近似数值，仅用于展示计算，不回传 */
function approx(value: unknown): number {
  const result = Number(value);
  return Number.isFinite(result) ? result : 0;
}

export function edgeKey(sourceNo: string, targetNo: string) {
  return JSON.stringify([sourceNo, targetNo]);
}

export function summarizeRuntimeEdges(
  metrics: null | RuntimeMetrics | undefined,
  names: Map<string, string> = new Map(),
): RuntimeEdgeSummary[] {
  if (!metrics) return [];
  const elapsed = approx(metrics.elapsedMillis);
  return (metrics.edges ?? []).map((edge) => {
    const blockedMillis = approx(edge.blockedMillis);
    const starvedMillis = approx(edge.starvedMillis);
    const capacity = approx(edge.capacity);
    const queued = approx(edge.queued);
    const blockedRatio =
      elapsed > 0 ? Math.min(1, blockedMillis / elapsed) : null;
    const starvedRatio =
      elapsed > 0 ? Math.min(1, starvedMillis / elapsed) : null;
    const fill = !metrics.finished && capacity > 0 ? queued / capacity : 0;
    let level: PressureLevel = 'normal';
    if ((blockedRatio ?? 0) >= HIGH_PRESSURE_RATIO || fill >= HIGH_FILL_RATIO)
      level = 'high';
    else if (
      (blockedRatio ?? 0) >= MEDIUM_PRESSURE_RATIO ||
      fill >= MEDIUM_FILL_RATIO
    )
      level = 'medium';
    return {
      key: edgeKey(edge.sourceNo, edge.targetNo),
      sourceNo: edge.sourceNo,
      targetNo: edge.targetNo,
      sourceName: names.get(edge.sourceNo) ?? edge.sourceNo,
      targetName: names.get(edge.targetNo) ?? edge.targetNo,
      queued,
      capacity,
      delivered: edge.delivered,
      blockedMillis,
      starvedMillis,
      blockedRatio,
      starvedRatio,
      level,
    };
  });
}

/** 上游等待占比最高的连线，其下游节点处理慢于上游；低于中等阈值时不提示 */
export function findBottleneck(edges: RuntimeEdgeSummary[]) {
  let worst: RuntimeEdgeSummary | undefined;
  for (const edge of edges) {
    const ratio = edge.blockedRatio ?? 0;
    if (ratio < MEDIUM_PRESSURE_RATIO) continue;
    if (!worst || ratio > (worst.blockedRatio ?? 0)) worst = edge;
  }
  return worst;
}

/** 相邻采样点的近似吞吐（行/秒）；时间未前进的点跳过，计数回退按 0 处理 */
export function throughputTrend(
  series: DataforgeEtlApi.RuntimePoint[] | null | undefined,
): ThroughputPoint[] {
  const points: ThroughputPoint[] = [];
  const list = series ?? [];
  for (let index = 1; index < list.length; index++) {
    const previous = list[index - 1];
    const current = list[index];
    if (!previous || !current) continue;
    const seconds =
      (approx(current.elapsedMillis) - approx(previous.elapsedMillis)) / 1000;
    if (seconds <= 0) continue;
    const rows = Math.max(
      0,
      approx(current.transferredRows) - approx(previous.transferredRows),
    );
    points.push({
      elapsedMillis: approx(current.elapsedMillis),
      rowsPerSecond: rows / seconds,
    });
  }
  return points;
}

/** 运行中节点改用快照的实时计数；已结束节点以节点记录的终态计数为准 */
export function withLiveCounts(
  records: DataforgeEtlApi.NodeRecord[],
  metrics: null | RuntimeMetrics | undefined,
): DataforgeEtlApi.NodeRecord[] {
  if (!metrics?.nodes?.length) return records;
  const live = new Map(metrics.nodes.map((node) => [node.nodeNo, node]));
  return records.map((record) => {
    const sample =
      record.runStatus === 2 && record.nodeNo
        ? live.get(record.nodeNo)
        : undefined;
    return sample
      ? {
          ...record,
          readCount: sample.readCount,
          writeCount: sample.writeCount,
        }
      : record;
  });
}

const LEVEL_WEIGHT: Record<PressureLevel, number> = {
  high: 4,
  medium: 2,
  normal: 0,
};

function pressureRank(edge: RuntimeEdgeSummary) {
  return LEVEL_WEIGHT[edge.level] + (edge.blockedRatio ?? 0);
}

/**
 * 按节点记录的设计节点把运行时连线归并到画布连线：子流程内部连线没有对应画布连线而被忽略，
 * 多条运行时连线落到同一画布连线时保留压力最高的一条。
 */
export function mapEdgesToDesign(
  edges: RuntimeEdgeSummary[],
  records: DataforgeEtlApi.NodeRecord[],
) {
  const designOf = new Map<string, string>();
  for (const record of records)
    if (record.nodeNo)
      designOf.set(record.nodeNo, record.designNodeNo ?? record.nodeNo);
  const result = new Map<string, RuntimeEdgeSummary>();
  for (const edge of edges) {
    const source = designOf.get(edge.sourceNo) ?? edge.sourceNo;
    const target = designOf.get(edge.targetNo) ?? edge.targetNo;
    if (source === target) continue;
    const key = edgeKey(source, target);
    const existing = result.get(key);
    if (!existing || pressureRank(edge) > pressureRank(existing))
      result.set(key, edge);
  }
  return result;
}

export function nodeNameMap(records: DataforgeEtlApi.NodeRecord[]) {
  const names = new Map<string, string>();
  for (const record of records)
    if (record.nodeNo)
      names.set(record.nodeNo, record.nodeName ?? record.nodeNo);
  return names;
}

/** 画布连线标签：当前排队数与上游累计等待 */
export function edgeRuntimeText(edge: RuntimeEdgeSummary) {
  return `队列 ${edge.queued}/${edge.capacity} · 等待 ${formatDurationMs(edge.blockedMillis)}`;
}

export function formatRatio(ratio: null | number | undefined) {
  return ratio === null || ratio === undefined
    ? '-'
    : `${Math.round(ratio * 100)}%`;
}

export function formatBytes(value: null | number | string | undefined) {
  const bytes = approx(value);
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let size = bytes / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(1)} ${units[unit]}`;
}
