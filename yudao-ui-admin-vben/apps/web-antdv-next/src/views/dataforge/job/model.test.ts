import type { OpsInstanceApi } from '#/api/dataforge/ops';

import { describe, expect, it } from 'vitest';

import { hasActiveJobRuns, jobStrategyHelp, shouldObserveJob } from './model';

const record: OpsInstanceApi.Instance = {
  id: '9007199254740993', kind: 'JOB', origin: 'PLATFORM',
  taskId: '9007199254740995', taskName: '串行作业', instanceName: '串行作业-3',
  executionType: 'START', status: 'STOPPED', queueState: 'DISCARDED',
};

describe('作业级队列控制', () => {
  it('最新请求被抛弃时，服务端活动实例仍保留停止入口', () => {
    expect(hasActiveJobRuns({ latestRecord: record, lastRunStatus: 'STOPPED', hasActiveRuns: true, activeRunCount: 1, queuedRunCount: 0 })).toBe(true);
  });
  it('只有排队或未知执行结果时仍可停止，全部结束后才关闭入口', () => {
    expect(hasActiveJobRuns({ lastRunStatus: 'WAITING', activeRunCount: 0, queuedRunCount: 2 })).toBe(true);
    expect(hasActiveJobRuns({ lastRunStatus: 'UNKNOWN' })).toBe(true);
    expect(hasActiveJobRuns({ activeRunCount: '9007199254740993', queuedRunCount: '0' })).toBe(true);
    expect(hasActiveJobRuns({ lastRunStatus: 'RUNNING', hasActiveRuns: false, activeRunCount: 0, queuedRunCount: 0 })).toBe(false);
  });
  it('串行优先说明包含替换、停止确认边界，串行抛弃不承诺执行', () => {
    expect(jobStrategyHelp.PARALLEL).toContain('开发节点仍遵守单任务并发限制');
    expect(jobStrategyHelp.PARALLEL).toContain('资源忙会拒绝本次执行');
    expect(jobStrategyHelp.SERIAL_PRIORITY).toContain('确认旧实例全部停止后');
    expect(jobStrategyHelp.SERIAL_DISCARD).toContain('抛弃新请求');
  });
  it('周期空闲时继续观察下一次触发，停止入口仍只取决于活动实例', () => {
    const idle = { scheduleStatus: 1, hasActiveRuns: false, activeRunCount: 0, queuedRunCount: 0 };
    expect(shouldObserveJob(idle)).toBe(true);
    expect(hasActiveJobRuns(idle)).toBe(false);
  });
  it('周期关闭且全部实例结束时停止观察，仍有活动实例则继续观察', () => {
    expect(shouldObserveJob({ scheduleStatus: 0, hasActiveRuns: false, latestRecord: { ...record, queueState: 'DONE', status: 'SUCCESS' } })).toBe(false);
    expect(shouldObserveJob({ scheduleStatus: 0, hasActiveRuns: true })).toBe(true);
  });
});
