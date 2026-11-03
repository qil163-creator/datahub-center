/* eslint-disable vue/one-component-per-file -- 隔离控件，验证真实运行命令和会话边界。 */
import type { App } from 'vue';

import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { executeBatchFile, getBatchFileRuns } from '#/api/dataforge/batchfile';

import RunList from './run-list.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'actor-a', refreshToken: '', accessCodes: ['all'] },
  confirm: undefined as
    | undefined
    | { onOk: () => Promise<unknown> | undefined },
  task: {} as Api.Detail,
  destroy: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: [] }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: () => harness.access.accessCodes.length > 0,
  }),
}));
vi.mock('./grid-tools.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));
vi.mock('./pagination.vue', () => ({ default: () => null }));
vi.mock('#/api/dataforge/batchfile', () => ({
  executeBatchFile: vi.fn(),
  getBatchFileRuns: vi.fn(),
  getBatchFileRun: vi.fn(),
  resolveBatchFileRun: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Checkbox: box,
    Spin: box,
    TextArea: box,
    message: { success: vi.fn() },
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Modal: Object.assign(
      defineComponent({
        props: { open: Boolean },
        setup:
          (props, { slots }) =>
          () =>
            props.open ? h('div', slots.default?.()) : null,
      }),
      {
        confirm: vi.fn((config) => {
          harness.confirm = config;
          return { destroy: harness.destroy };
        }),
      },
    ),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
async function settle() {
  for (let index = 0; index < 10; index++) await nextTick();
}
async function mount() {
  app = createApp(
    defineComponent({ setup: () => () => h(RunList, { task: harness.task }) }),
  );
  app.mount(host);
  await settle();
}
function executeButton() {
  return [...host.querySelectorAll('button')].find((button) =>
    button.textContent?.includes('执行一次'),
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.confirm = undefined;
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['all'],
  });
  harness.task = reactive({
    id: '9007199254740993',
    online: true,
    lastStatus: 'NOT_EXECUTED',
  } as Api.Detail);
  vi.mocked(getBatchFileRuns).mockResolvedValue({ list: [], total: 0 });
  vi.mocked(executeBatchFile).mockResolvedValue({
    id: '9007199254740995',
    status: 'QUEUED',
    replayed: false,
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('文件入库运行命令', () => {
  it('确认前不执行；响应丢失后重试复用 requestId 与无损任务编号', async () => {
    await mount();
    vi.mocked(executeBatchFile).mockRejectedValueOnce(
      new Error('response lost'),
    );
    executeButton().click();
    expect(executeBatchFile).not.toHaveBeenCalled();
    await expect(harness.confirm!.onOk()).rejects.toThrow('response lost');
    await settle();
    executeButton().click();
    await harness.confirm!.onOk();
    await settle();
    const calls = vi.mocked(executeBatchFile).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]![0]).toEqual(calls[1]![0]);
    expect(calls[0]![0].id).toBe('9007199254740993');
    expect(calls[0]![0].requestId).toMatch(/^[\da-f-]{36}$/u);
  });
  it('旧会话的执行确认不得在新会话中提交', async () => {
    await mount();
    executeButton().click();
    const stale = harness.confirm!;
    harness.access.accessToken = 'actor-b';
    await settle();
    expect(harness.destroy).toHaveBeenCalledOnce();
    await stale.onOk();
    expect(executeBatchFile).not.toHaveBeenCalled();
  });
  it('下线和未核查的 UNKNOWN 禁止执行，已核查且重新上线后可执行', async () => {
    harness.task.online = false;
    await mount();
    expect(executeButton().disabled).toBe(true);
    harness.task.online = true;
    harness.task.lastStatus = 'UNKNOWN';
    await settle();
    expect(executeButton().disabled).toBe(true);
    harness.task.lastRunResolvedTime = 1_792_127_460_000;
    await settle();
    expect(executeButton().disabled).toBe(false);
  });
});
