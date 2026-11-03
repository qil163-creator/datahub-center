import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

export const RUN_STATUS = {
  WAITING: 1,
  RUNNING: 2,
  SUCCESS: 3,
  FAILED: 4,
  UNKNOWN: 5,
  RESOLVED: 6,
} as const;

type RunState = Pick<
  DataforgeDataPushTaskApi.Task,
  'blockingRunStatus' | 'lastRunStatus' | 'running'
>;

export function hasUnknownOutcome(task: RunState): boolean {
  return (task.blockingRunStatus ?? task.lastRunStatus) === RUN_STATUS.UNKNOWN;
}

export function isExecutionBlocked(task: RunState): boolean {
  return (
    hasUnknownOutcome(task) ||
    !!task.running ||
    task.blockingRunStatus === RUN_STATUS.WAITING ||
    task.blockingRunStatus === RUN_STATUS.RUNNING
  );
}

export function runStateLabel(task: RunState): string {
  if (hasUnknownOutcome(task)) return '待核查（禁止重跑）';
  if (task.blockingRunStatus === RUN_STATUS.WAITING) return '等待发送';
  return task.running ? '运行中' : '空闲';
}
