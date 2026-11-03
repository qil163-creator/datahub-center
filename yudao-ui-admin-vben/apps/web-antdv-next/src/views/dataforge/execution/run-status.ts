import type { RunId } from '#/api/dataforge/execution/command';

export type RunDomain = 'ACCESS' | 'ETL';
export interface RunStateSource {
  effectState?: string;
  occupiesSlot?: boolean;
  qualityStatus?: string;
  reviewRequired?: boolean;
  rowVersion?: RunId;
  runStatus?: number;
  stopRequested?: boolean;
}

export function getRunState(domain: RunDomain, record: RunStateSource) {
  const status = record.runStatus;
  const unknown = status === (domain === 'ACCESS' ? 5 : 6);
  const stopped = status === (domain === 'ACCESS' ? 6 : 5);
  const active = status === 1 || status === 2;
  const qualityPending = domain === 'ETL' && record.qualityStatus === 'PENDING';
  const qualityUnknown = domain === 'ETL' && record.qualityStatus === 'UNKNOWN';
  const review =
    unknown ||
    qualityUnknown ||
    record.reviewRequired === true ||
    (record.occupiesSlot === true && !active && !qualityPending);
  let label = '未知状态';
  let color = 'default';
  if (review) {
    label = '结果待核查';
    color = 'warning';
  } else if (record.stopRequested && active) {
    label = '取消已受理 / 待确认';
    color = 'warning';
  } else if (qualityPending) {
    label = '质量检查中';
    color = 'processing';
  } else if (status === 1) {
    label = '排队中';
    color = 'default';
  } else if (status === 2) {
    label = '执行中';
    color = 'processing';
  } else if (status === 3) {
    label = '执行成功';
    color = 'success';
  } else if (status === 4) {
    label = '执行失败';
    color = 'error';
  } else if (stopped) {
    label = '已确认停止';
    color = 'default';
  }
  return {
    canDelete:
      !active &&
      !review &&
      !qualityPending &&
      !record.occupiesSlot &&
      (status === 3 || status === 4 || stopped),
    canResolve:
      review && record.occupiesSlot === true && record.rowVersion !== undefined,
    canStop: (active || unknown) && !record.stopRequested,
    color,
    label,
    shouldPoll:
      active ||
      unknown ||
      qualityPending ||
      qualityUnknown ||
      record.occupiesSlot === true,
  };
}
