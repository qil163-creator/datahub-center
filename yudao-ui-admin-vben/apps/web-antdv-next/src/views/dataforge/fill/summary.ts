import { WORK_ITEM_STATUS_TEXT } from './shared';

/** 未报：待填报、填报中、整改中、结束填报时取消（与服务端口径一致） */
export const UNREPORTED_STATUSES = new Set([0, 1, 5, 10]);

/** 期次矩阵单元格：状态文字与颜色；不在该期名单内时为空 */
export function periodCell(
  statuses: Record<string, number>,
  overdue: Record<string, number>,
  taskId: number | string,
): undefined | { color: string; text: string } {
  const status = statuses[String(taskId)];
  if (status === undefined || status === null) return undefined;
  const display = WORK_ITEM_STATUS_TEXT[status];
  const late = overdue[String(taskId)] === 1;
  const text = `${display?.text ?? status}${late ? '·逾期' : ''}`;
  if (UNREPORTED_STATUSES.has(status)) {
    return { color: late ? 'error' : 'warning', text };
  }
  return { color: display?.color ?? 'default', text };
}

/** 一个组织在矩阵内的未报期数 */
export function countUnreported(statuses: Record<string, number>): number {
  return Object.values(statuses).filter((status) => UNREPORTED_STATUSES.has(status)).length;
}
