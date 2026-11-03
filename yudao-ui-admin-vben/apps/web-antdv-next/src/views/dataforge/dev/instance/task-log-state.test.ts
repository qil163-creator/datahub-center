import { describe, expect, it } from 'vitest';

import { appendTaskLogPage, createTaskLogState } from './task-log-state';

describe('实例执行日志分页', () => {
  it('按服务端游标推进，正文脱敏后不重新计算行数', () => {
    const state = createTaskLogState();
    appendTaskLogPage(state, {
      message: 'redacted single line',
      readLines: 3,
      nextSkipLineNum: 7,
      mayHaveMore: true,
      truncated: false,
      available: true,
      source: 'DOLPHINSCHEDULER',
    });
    expect(state.cursor).toBe(7);
    expect(state.mayHaveMore).toBe(true);
    appendTaskLogPage(state, {
      message: '',
      readLines: 0,
      nextSkipLineNum: 7,
      mayHaveMore: false,
      truncated: false,
      available: true,
      source: 'DOLPHINSCHEDULER',
    });
    expect(state.content).toBe('redacted single line');
    expect(state.mayHaveMore).toBe(false);
  });

  it('日志暂不可用时保留原游标与内容，允许再次读取', () => {
    const state = { ...createTaskLogState(), content: 'previous', cursor: 10 };
    appendTaskLogPage(state, {
      message: '',
      readLines: 0,
      nextSkipLineNum: 0,
      mayHaveMore: false,
      truncated: false,
      available: false,
      source: 'DOLPHINSCHEDULER',
      statusMessage: '稍后重试',
    });
    expect(state.cursor).toBe(10);
    expect(state.content).toBe('previous');
    expect(state.mayHaveMore).toBe(true);
    expect(state.statusMessage).toBe('稍后重试');
  });

  it('长期任务只保留有界正文，截断状态明确可见', () => {
    const state = createTaskLogState();
    appendTaskLogPage(state, {
      message: 'x'.repeat(256 * 1024),
      readLines: 1,
      nextSkipLineNum: 1,
      mayHaveMore: true,
      truncated: false,
      available: true,
      source: 'DOLPHINSCHEDULER',
    });
    expect(state.content.length).toBe(128 * 1024);
    expect(state.truncated).toBe(true);
    expect(state.cursor).toBe(1);
  });
});
