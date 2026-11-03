import { describe, expect, it } from 'vitest';

import {
  hasUnknownOutcome,
  isExecutionBlocked,
  runStateLabel,
} from './run-state';

describe('推送持久未知状态', () => {
  it('未知不展示为空闲，禁止再次执行及修改', () => {
    const task = { blockingRunStatus: 5, lastRunStatus: 3, running: false };
    expect(hasUnknownOutcome(task)).toBe(true);
    expect(isExecutionBlocked(task)).toBe(true);
    expect(runStateLabel(task)).toBe('待核查（禁止重跑）');
  });
  it('发送前等待和发送中均占位', () => {
    expect(isExecutionBlocked({ blockingRunStatus: 1 })).toBe(true);
    expect(runStateLabel({ blockingRunStatus: 1 })).toBe('等待发送');
    expect(isExecutionBlocked({ blockingRunStatus: 2, running: true })).toBe(
      true,
    );
    expect(runStateLabel({ blockingRunStatus: 2, running: true })).toBe(
      '运行中',
    );
  });
  it('旧响应未知状态仍保守禁止重跑', () => {
    expect(isExecutionBlocked({ lastRunStatus: 5 })).toBe(true);
    expect(isExecutionBlocked({ running: true })).toBe(true);
  });
  it('无占位的确定性失败或成功可再次执行', () => {
    expect(isExecutionBlocked({ blockingRunStatus: 0, lastRunStatus: 4 })).toBe(
      false,
    );
    expect(isExecutionBlocked({ blockingRunStatus: 0, lastRunStatus: 3 })).toBe(
      false,
    );
    expect(runStateLabel({ blockingRunStatus: 0 })).toBe('空闲');
  });
});
