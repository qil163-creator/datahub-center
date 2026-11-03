import type { JobApi as Api } from '#/api/dataforge/job';

import { isActiveOpsInstance } from '../ops/model';

export { strategyOptions } from '../integration/model';
export { displayDate, formatDuration, statusOptions as runStatusOptions } from '../ops/model';

export function jobCategoryTree(categories: Api.Category[], keyword = '') {
  interface Node {
    key: string;
    value: string;
    title: string;
    isLeaf: boolean;
    children?: Node[];
  }
  const map = new Map<string, Node>(
    categories.map((row) => [
      String(row.id),
      {
        key: String(row.id),
        value: String(row.id),
        title: row.name,
        isLeaf: true,
      },
    ]),
  );
  const roots: Node[] = [];
  for (const row of categories) {
    const node = map.get(String(row.id))!;
    const parent = map.get(String(row.parentId));
    if (parent && parent !== node) {
      (parent.children ??= []).push(node);
      parent.isLeaf = false;
    } else roots.push(node);
  }
  const query = keyword.trim().toLocaleLowerCase();
  const filter = (nodes: Node[]): Node[] =>
    nodes.flatMap((node) => {
      if (!query || node.title.toLocaleLowerCase().includes(query))
        return [node];
      const children = filter(node.children ?? []);
    return children.length > 0 ? [{ ...node, children }] : [];
    });
  return filter(roots);
}

export function jobSave(job: Api.Job): Api.SaveReq {
  return {
    id: job.id,
    revision: job.revision,
    categoryId: job.categoryId,
    name: job.name,
    ownerId: job.ownerId,
    ownerPhone: job.ownerPhone,
    strategy: job.strategy,
    cronExpression: job.cronExpression,
    description: job.description,
    flowJson: job.flowJson,
  };
}


export const jobStrategyHelp: Record<Api.Strategy, string> = {
  PARALLEL: '允许同一作业的多个实例同时执行。开发节点仍遵守单任务并发限制，资源忙会拒绝本次执行。',
  SERIAL_WAIT: '按请求顺序排队，当前实例结束后再执行下一个实例。',
  SERIAL_DISCARD: '已有未结束的运行或排队请求时，抛弃新请求并保留原因。',
  SERIAL_PRIORITY: '替换旧排队请求并请求停止当前实例；确认旧实例全部停止后，再执行新请求。',
};

/** 活动实例与排队数量由服务端汇总，最新丢弃记录不会遮住停止入口。 */
export function hasActiveJobRuns(job: Pick<Api.Job, 'activeRunCount' | 'hasActiveRuns' | 'lastRunStatus' | 'latestRecord' | 'queuedRunCount'>) {
  if (job.hasActiveRuns !== undefined) return job.hasActiveRuns;
  if (job.activeRunCount !== undefined || job.queuedRunCount !== undefined)
    return [job.activeRunCount, job.queuedRunCount].some((value) =>
      typeof value === 'number' ? value > 0 : typeof value === 'string' && /^[1-9]\d*$/u.test(value),
    );
  return job.latestRecord
    ? isActiveOpsInstance(job.latestRecord)
    : !!job.lastRunStatus && isActiveOpsInstance({ status: job.lastRunStatus });
}

/** 周期已开启时，即使两次触发之间没有活动实例，也继续观察下一次调度。 */
export function shouldObserveJob(
  job: Parameters<typeof hasActiveJobRuns>[0] & Pick<Api.Job, 'scheduleStatus'>,
) {
  return job.scheduleStatus === 1 || hasActiveJobRuns(job);
}
