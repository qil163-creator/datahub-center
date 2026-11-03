/* eslint-disable vue/one-component-per-file -- 隔离组件替身用于验证弹窗的请求与轮询生命周期。 */
import type { App } from 'vue';

import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/ops';

import LogModal from './log-modal.vue';

vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/utils', () => ({ downloadFileFromBlobPart: vi.fn() }));
vi.mock('#/api/dataforge/ops', () => ({ getOpsInstance: vi.fn(), getOpsInstanceLog: vi.fn(), downloadOpsInstanceLog: vi.fn() }));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  return {
    Alert: Container, Input: Container, Spin: Container, Switch: Container,
    Button: defineComponent({ setup: (_props, { slots }) => () => h('button', slots.default?.()) }),
    Modal: defineComponent({ props: { open: Boolean }, setup: (props, { slots }) => () => props.open ? h('section', [slots.default?.(), slots.footer?.()]) : null }),
  };
});

const fixture = (origin: Api.Origin = 'SOURCE', status: Api.Status = 'RUNNING'): Api.Instance => ({ id: '9007199254740993', kind: 'DEVELOPMENT', origin, taskId: '9007199254740995', taskName: '实时监测', instanceName: '实时监测-1', executionType: 'START', status });
let app: App;
let host: HTMLDivElement;
const open = ref(true);
const row = ref<Api.Instance>(fixture());
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await nextTick(); };
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); open.value = true; row.value = fixture();
  vi.mocked(api.getOpsInstance).mockImplementation(async (identity) => ({ ...fixture(identity.origin), ...identity }));
  vi.mocked(api.getOpsInstanceLog).mockResolvedValue({ available: true, message: '[2026-10-22 19:11:00] INFO 原记录日志', importedSnapshot: true });
  host = document.createElement('div'); document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(LogModal, { row: row.value, open: open.value }) }));
  app.use(createPinia()); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); vi.clearAllTimers(); vi.useRealTimers(); });

describe('运维日志弹窗请求生命周期', () => {
  it('运行态每三秒刷新，关闭后停止且不恢复轮询', async () => {
    await flush(); expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(3000); expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(2);
    open.value = false; await flush(); await vi.advanceTimersByTimeAsync(9000);
    expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(2);
  });
  it('同编号来源切换后旧响应不能覆盖新来源日志', async () => {
    await flush();
    let resolveOld: (value: Api.Log) => void = () => {};
    vi.mocked(api.getOpsInstanceLog).mockImplementationOnce(() => new Promise<Api.Log>((resolve) => { resolveOld = resolve; }));
    await vi.advanceTimersByTimeAsync(3000);
    vi.mocked(api.getOpsInstanceLog).mockResolvedValue({ available: true, message: '[2026-10-22 19:12:00] INFO 平台新日志', importedSnapshot: false });
    row.value = fixture('PLATFORM'); await flush();
    resolveOld({ available: true, message: '[2026-10-22 19:11:00] INFO 不应回写的旧日志', importedSnapshot: true }); await flush();
    expect(host.textContent).toContain('平台新日志'); expect(host.textContent).not.toContain('不应回写');
    expect(api.getOpsInstanceLog).toHaveBeenLastCalledWith({ id: '9007199254740993', kind: 'DEVELOPMENT', origin: 'PLATFORM' });
  });
  it('排队审计可读且持续刷新，转入丢弃终态后停止刷新', async () => {
    await flush();
    const queued: Api.Instance = { ...fixture('PLATFORM', 'WAITING'), kind: 'JOB', queueState: 'QUEUED', queueReason: '等待当前实例结束' };
    vi.mocked(api.getOpsInstance).mockResolvedValue(queued);
    vi.mocked(api.getOpsInstanceLog).mockResolvedValue({ available: true, message: '真实队列审计：等待当前实例结束', importedSnapshot: false });
    row.value = queued; await flush();
    const initial = vi.mocked(api.getOpsInstanceLog).mock.calls.length;
    await vi.advanceTimersByTimeAsync(3000); await flush();
    expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(initial + 1);
    expect(host.textContent).toContain('排队中');
    expect(host.textContent).toContain('等待当前实例结束');
    vi.mocked(api.getOpsInstance).mockResolvedValue({ ...queued, status: 'STOPPED', queueState: 'DISCARDED', queueReason: '串行抛弃：已有未结束请求' });
    await vi.advanceTimersByTimeAsync(3000); await flush();
    const terminal = vi.mocked(api.getOpsInstanceLog).mock.calls.length;
    expect(host.textContent).toContain('已抛弃');
    expect(host.textContent).toContain('串行抛弃：已有未结束请求');
    await vi.advanceTimersByTimeAsync(9000);
    expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(terminal);
  });
  it('刷新发现终态后自动关闭轮询', async () => {
    await flush();
    vi.mocked(api.getOpsInstance).mockResolvedValue(fixture('SOURCE', 'SUCCESS'));
    await vi.advanceTimersByTimeAsync(3000); await flush();
    const count = vi.mocked(api.getOpsInstanceLog).mock.calls.length;
    await vi.advanceTimersByTimeAsync(9000);
    expect(api.getOpsInstanceLog).toHaveBeenCalledTimes(count);
  });
});
