/* eslint-disable vue/one-component-per-file -- 隔离表格控件，验证真实API游标、会话与轮询生命周期。 */
import type { App, PropType } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import ExecutionPanel from './workbench-execution-panel.vue';

const permissions = vi.hoisted(
  () => new Set(['dataforge:dev-task:execute-native']),
);
const session = reactive({ accessToken: 'session', refreshToken: 'refresh' });
vi.mock('@vben/stores', () => ({ useAccessStore: () => session }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => permissions.has(code)),
  }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
  downloadFileFromBlobPart: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevRecordPage: vi.fn(),
  getDevRecord: vi.fn(),
  getDevRecordTaskLog: vi.fn(),
  downloadDevRecordLog: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Input: Container,
    Checkbox: Container,
    Pagination: Container,
    Tag: Container,
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', props.message),
    }),
    Button: defineComponent({
      emits: ['click'],
      setup:
        (_, { emit, slots }) =>
        () =>
          h('button', { onClick: () => emit('click') }, slots.default?.()),
    }),
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<DataforgeDevApi.Record[]>,
          default: () => [],
        },
        columns: {
          type: Array as PropType<{ key: string }[]>,
          default: () => [],
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.flatMap((record) =>
              props.columns.map((column) =>
                slots.bodyCell?.({ record, column }),
              ),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
let settings: { mode: 'history' | 'log'; recordId?: string; taskId: string };
const record: DataforgeDevApi.Record = {
  id: '9007199254740993',
  taskId: '9007199254740995',
  taskName: '原生任务',
  status: 'RUNNING',
  nativeProgram: true,
  dsInstanceId: 31,
};
async function flush() {
  for (let index = 0; index < 20; index++) await Promise.resolve();
  await nextTick();
}
async function mount(mode: 'history' | 'log', openLog = vi.fn()) {
  settings = reactive({ mode, taskId: String(record.taskId) });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(
    defineComponent(
      () => () => h(ExecutionPanel, { ...settings, onOpenLog: openLog }),
    ),
  );
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  permissions.add('dataforge:dev-task:execute-native');
  session.accessToken = 'session';
  session.refreshToken = 'refresh';
  vi.mocked(devApi.getDevRecordPage).mockResolvedValue({
    list: [{ ...record }],
    total: 1,
  });
  vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...record });
  vi.mocked(devApi.getDevRecordTaskLog).mockResolvedValue({
    available: true,
    message: '启动',
    readLines: 1,
    nextSkipLineNum: 9,
    mayHaveMore: false,
    truncated: false,
    source: 'DOLPHINSCHEDULER',
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.useRealTimers();
});
describe('工作台内嵌执行记录和日志', () => {
  it('日志请求挂起仍独立刷新提交/运行/停止状态且不重叠相同cursor请求', async () => {
    vi.useFakeTimers();
    let finishLog: ((value: DataforgeDevApi.TaskLogPage) => void) | undefined;
    vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...record, status: 'SUBMITTING' });
    vi.mocked(devApi.getDevRecordTaskLog).mockImplementationOnce(() => new Promise(resolve => { finishLog = resolve; }));
    await mount('log');
    expect(host.querySelector('.execution-tools')?.textContent).toContain('提交中');
    vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...record, status: 'RUNNING' });
    await vi.advanceTimersByTimeAsync(3000); await flush();
    expect(host.querySelector('.execution-tools')?.textContent).toContain('运行中');
    expect(devApi.getDevRecordTaskLog).toHaveBeenCalledTimes(1);
    vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...record, status: 'STOPPED' });
    await vi.advanceTimersByTimeAsync(3000); await flush();
    expect(host.querySelector('.execution-tools')?.textContent).toContain('已停止');
    expect(devApi.getDevRecordTaskLog).toHaveBeenCalledTimes(1);
    finishLog?.({ available: true, message: '完成', nextSkipLineNum: 8, readLines: 1, mayHaveMore: false, truncated: false, source: 'DOLPHINSCHEDULER' });
    await flush();
    const calls = vi.mocked(devApi.getDevRecord).mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(devApi.getDevRecord).toHaveBeenCalledTimes(calls);
  });
  it('日志挂起期间换会话丢弃旧正文且新会话继续读取', async () => {
    let finishOld: ((value: DataforgeDevApi.TaskLogPage) => void) | undefined;
    vi.mocked(devApi.getDevRecordTaskLog).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }));
    await mount('log');
    session.accessToken = 'new-user-session'; await flush();
    expect(host.querySelector('pre')?.textContent).toBe('启动');
    finishOld?.({ available: true, message: '旧会话秘密', nextSkipLineNum: 90, readLines: 1, mayHaveMore: false, truncated: false, source: 'DOLPHINSCHEDULER' });
    await flush();
    expect(host.textContent).not.toContain('旧会话秘密');
  });
  it('手动读取最新实例改变ID时重置游标，不把旧实例正文带入新日志', async () => {
    await mount('log');
    const next = { ...record, id: '9007199254740997' };
    vi.mocked(devApi.getDevRecordPage).mockResolvedValue({ list: [next], total: 1 });
    vi.mocked(devApi.getDevRecord).mockResolvedValue(next);
    vi.mocked(devApi.getDevRecordTaskLog).mockResolvedValue({ available: true, message: '新实例', readLines: 1, nextSkipLineNum: 3, mayHaveMore: false, truncated: false, source: 'DOLPHINSCHEDULER' });
    [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '刷新')?.click(); await flush();
    expect(devApi.getDevRecordTaskLog).toHaveBeenLastCalledWith({ id: next.id, skipLineNum: 0, limit: 300 });
    expect(host.querySelector('pre')?.textContent).toBe('新实例');
  });
  it('历史按当前任务读取真实分页，日志操作发出原始大ID', async () => {
    const openLog = vi.fn();
    await mount('history', openLog);
    expect(devApi.getDevRecordPage).toHaveBeenCalledWith({
      taskId: record.taskId,
      pageNo: 1,
      pageSize: 10,
    });
    expect(host.textContent).toContain('#31');
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('查看日志'))
      ?.click();
    expect(openLog).toHaveBeenCalledWith(record.id);
    expect(devApi.getDevRecordTaskLog).not.toHaveBeenCalled();
  });
  it('日志追尾使用服务端游标，终态读尽后停止轮询', async () => {
    vi.useFakeTimers();
    await mount('log');
    expect(devApi.getDevRecordTaskLog).toHaveBeenLastCalledWith({
      id: record.id,
      skipLineNum: 0,
      limit: 300,
    });
    vi.mocked(devApi.getDevRecord).mockResolvedValue({
      ...record,
      status: 'STOPPED',
    });
    vi.mocked(devApi.getDevRecordTaskLog).mockResolvedValue({
      available: true,
      message: '停止完成',
      readLines: 1,
      nextSkipLineNum: 12,
      mayHaveMore: false,
      truncated: false,
      source: 'DOLPHINSCHEDULER',
    });
    await vi.advanceTimersByTimeAsync(3000);
    await flush();
    expect(devApi.getDevRecordTaskLog).toHaveBeenLastCalledWith({
      id: record.id,
      skipLineNum: 9,
      limit: 300,
    });
    expect(host.querySelector('pre')?.textContent).toBe('启动\n停止完成');
    const calls = vi.mocked(devApi.getDevRecordTaskLog).mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(devApi.getDevRecordTaskLog).toHaveBeenCalledTimes(calls);
  });
  it('原生日志权限不足不请求日志正文，切换会话清空旧正文并废弃旧返回', async () => {
    permissions.clear();
    await mount('log');
    expect(devApi.getDevRecordTaskLog).not.toHaveBeenCalled();
    expect(host.textContent).toContain('原生执行日志需单独授权');
    permissions.add('dataforge:dev-task:execute-native');
    session.accessToken = 'next';
    await flush();
    expect(host.querySelector('pre')?.textContent).toBe('启动');
    let resolveOld: ((value: DataforgeDevApi.Record) => void) | undefined;
    vi.mocked(devApi.getDevRecord).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    settings.recordId = String(record.id);
    await flush();
    session.accessToken = 'newer';
    await flush();
    resolveOld?.({ ...record, log: '旧会话秘密' });
    await flush();
    expect(host.textContent).not.toContain('旧会话秘密');
    app?.unmount();
    app = undefined;
    expect(host.querySelector('pre')).toBeNull();
  });
});
