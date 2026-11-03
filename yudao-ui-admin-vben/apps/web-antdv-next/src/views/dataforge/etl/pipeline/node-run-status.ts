import type { DataforgeEtlApi } from '#/api/dataforge/etl';

/** 行数为后端 Long 契约，可能是 number 或十进制字符串；仅用于展示，不回传。 */
type CountValue = number | string;

/** 设计节点聚合后的运行态（写入画布节点 data 的临时字段） */
export interface DesignNodeRunStatus {
  id: string;
  status: number;
  /** 仅当设计节点只对应一条记录时透传；子流程多条子记录不求和，置为 undefined */
  readCount?: CountValue;
  writeCount?: CountValue;
}

/** 节点记录加诊断指标（耗时、吞吐、最慢标识） */
export interface NodeRecordSummary extends DataforgeEtlApi.NodeRecord {
  /** 毫秒耗时；缺少时间信息时为 null */
  durationMs: null | number;
  /** 只有开始时间且仍在运行时为 true（耗时按 now 推算） */
  running: boolean;
  /** 写入行数 / 秒；耗时 <= 0 或写入行数缺失时为 null */
  throughputPerSecond: null | number;
  /** 已完成记录中耗时最大的唯一一条 */
  slowest: boolean;
}

/** 节点级终态：3 成功 / 4 失败 / 5 已停止 */
const FINISHED_NODE_STATUSES = new Set([3, 4, 5]);

/** 节点记录是否已结束（成功 / 失败 / 已停止） */
export function isNodeRecordFinished(record: { runStatus?: null | number }) {
  return FINISHED_NODE_STATUSES.has(record.runStatus ?? 0);
}

/** 近似数值转换，仅服务展示计算；非有限数返回 null */
function toFiniteNumber(value: unknown): null | number {
  if (value === null || value === undefined || value === '') return null;
  const result = Number(value);
  return Number.isFinite(result) ? result : null;
}

/** Compiled children share a design call node; success requires every child to finish. */
export function groupNodeRunStatuses(
  records: DataforgeEtlApi.NodeRecord[],
): DesignNodeRunStatus[] {
  const groups = new Map<string, DataforgeEtlApi.NodeRecord[]>();
  for (const record of records) {
    const id = record.designNodeNo ?? record.nodeNo;
    if (!id) continue;
    groups.set(id, [...(groups.get(id) ?? []), record]);
  }
  return [...groups].map(([id, members]) => {
    const states = members.map((record) => record.runStatus ?? 1);
    let status = 2;
    if (states.includes(4)) status = 4;
    else if (states.includes(5)) status = 5;
    else if (states.every((value) => value === 3)) status = 3;
    else if (states.every((value) => value === 1)) status = 1;
    // 多条子记录对应同一设计节点时，行数语义不明确，不透传，避免误导
    const single = members.length === 1 ? members[0] : undefined;
    return {
      id,
      status,
      readCount: single?.readCount ?? undefined,
      writeCount: single?.writeCount ?? undefined,
    };
  });
}

/**
 * 为每条节点记录计算耗时、吞吐并标记最慢节点。
 * - 开始/结束时间齐全：耗时为差值；只有开始时间且运行中：按 now 推算并标记 running
 * - 吞吐 = 写入行数 / 秒，耗时 <= 0 或写入行数缺失时为 null
 * - 最慢节点只在已完成（3/4/5）且有耗时的记录中选唯一一条，全部未完成则无
 */
export function summarizeNodeRecords(
  records: DataforgeEtlApi.NodeRecord[],
  now: number = Date.now(),
): NodeRecordSummary[] {
  const summaries = records.map<NodeRecordSummary>((record) => {
    const start = toFiniteNumber(record.startTime);
    const end = toFiniteNumber(record.endTime);
    let durationMs: null | number = null;
    let running = false;
    if (start !== null && end !== null) {
      durationMs = Math.max(0, end - start);
    } else if (start !== null && record.runStatus === 2) {
      durationMs = Math.max(0, now - start);
      running = true;
    }
    const writeCount = toFiniteNumber(record.writeCount);
    const throughputPerSecond =
      durationMs !== null && durationMs > 0 && writeCount !== null
        ? writeCount / (durationMs / 1000)
        : null;
    return {
      ...record,
      durationMs,
      running,
      throughputPerSecond,
      slowest: false,
    };
  });
  let slowest: NodeRecordSummary | undefined;
  for (const summary of summaries) {
    if (!isNodeRecordFinished(summary) || summary.durationMs === null) continue;
    if (!slowest || summary.durationMs > (slowest.durationMs ?? -1))
      slowest = summary;
  }
  if (slowest) slowest.slowest = true;
  return summaries;
}

/** 毫秒耗时格式化：null→'-'，<1s→'xxx ms'，<60s→'x.x s'，否则 'Xm Ys' */
export function formatDurationMs(ms: null | number | undefined) {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '-';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.floor((ms % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}
