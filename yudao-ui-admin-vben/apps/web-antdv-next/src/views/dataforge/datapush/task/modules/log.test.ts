import type { App, SetupContext } from 'vue';

import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/datapush/task';

import Log from './log.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: number; refreshToken?: number },
  options: {} as { onOpenChange: (open: boolean) => Promise<void> },
  permissions: ['dataforge:data-push:resolve'],
  success: vi.fn(),
  warning: vi.fn(),
  errors: [] as unknown[],
  refreshed: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 99 } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      {
        getData: () => ({ id: '9007199254740993', name: '推送' }),
        close: () => options.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const slot =
    (tag: string) =>
    (_: unknown, { slots, attrs }: SetupContext) =>
      h(tag, attrs, slots.default?.());
  return {
    Button: slot('button'),
    Tag: slot('span'),
    Alert: slot('div'),
    Checkbox: (
      props: { checked?: boolean; disabled?: boolean },
      { emit, slots }: SetupContext,
    ) =>
      h('label', [
        h('input', {
          type: 'checkbox',
          checked: props.checked,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:checked', (event.target as HTMLInputElement).checked),
        }),
        slots.default?.(),
      ]),
    TextArea: (props: { value?: string }, { emit, attrs }: SetupContext) =>
      h('textarea', {
        ...attrs,
        value: props.value,
        onInput: (event: Event) =>
          emit('update:value', (event.target as HTMLTextAreaElement).value),
      }),
    Input: (props: { value?: string }, { emit, attrs }: SetupContext) =>
      h('input', {
        ...attrs,
        value: props.value,
        onInput: (event: Event) =>
          emit('update:value', (event.target as HTMLInputElement).value),
      }),
    Select: (
      props: {
        disabled?: boolean;
        options?: { label: string; value: number | string }[];
        value?: number | string;
      },
      { emit }: SetupContext,
    ) =>
      h(
        'select',
        {
          value: props.value,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:value', (event.target as HTMLSelectElement).value),
        },
        props.options?.map((option) =>
          h('option', { value: option.value }, option.label),
        ),
      ),
    message: { success: harness.success, warning: harness.warning },
    Modal: (
      props: { open?: boolean; title?: string },
      { emit, slots }: SetupContext,
    ) =>
      props.open
        ? h('aside', { 'aria-label': props.title }, [
            h(
              'button',
              { onClick: () => emit('update:open', false) },
              '关闭核查',
            ),
            slots.default?.(),
          ])
        : null,
    Table: Object.assign(
      (
        props: {
          columns: { key: string }[];
          dataSource?: DataforgeDataPushTaskApi.TaskLog[];
        },
        { slots }: SetupContext,
      ) =>
        h(
          'div',
          (props.dataSource ?? []).flatMap((record) =>
            props.columns.map((column) =>
              h('div', slots.bodyCell?.({ column, record })),
            ),
          ),
        ),
      { props: { columns: Array, dataSource: Array } },
    ),
  };
});
vi.mock('#/api/dataforge/datapush/task', () => ({
  getDataPushTaskLog: vi.fn(),
  getDataPushTaskLogPage: vi.fn(),
  resolveDataPushRun: vi.fn(),
}));
vi.mock('../../../common/log-viewer.vue', () => ({ default: () => null }));
vi.mock('../data', () => ({
  RUN_STATUS: { WAITING: 1, RUNNING: 2, UNKNOWN: 5, RESOLVED: 6 },
  RUN_STATUS_OPTIONS: [],
  RUN_TYPE_OPTIONS: [],
  optionColor: () => 'default',
  optionLabel: (_: unknown, value: unknown) => String(value),
}));
const record: DataforgeDataPushTaskApi.TaskLog = {
  id: '9007199254740995',
  pushTaskId: '9007199254740993',
  runStatus: 5,
  executeNo: 'execute-1',
  errorInfo: '未知',
};
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
  await nextTick();
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Log, { onSuccess: harness.refreshed });
  app.config.errorHandler = (e) => harness.errors.push(e);
  app.mount(host);
  await harness.options.onOpenChange(true);
  await flush();
}
async function edit(label: string, value: string) {
  const input = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `[aria-label="${label}"]`,
  )!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
async function ready() {
  button('核查处置')!.click();
  await flush();
  host
    .querySelectorAll<HTMLInputElement>('input[type=checkbox]')
    .forEach((input) => {
      input.checked = true;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
  await edit('核查原因', '已核查全部批次');
  await edit('终止证据编号', 'CASE-STOP-1');
  await edit('输出证据编号', 'CASE-OUTPUT-1');
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.errors = [];
  harness.access = reactive({ accessToken: 1, refreshToken: undefined });
  harness.permissions = ['dataforge:data-push:resolve'];
  vi.mocked(api.getDataPushTaskLogPage).mockResolvedValue({
    list: [record],
    total: 1,
  });
  vi.mocked(api.getDataPushTaskLog).mockResolvedValue({ ...record });
  vi.mocked(api.resolveDataPushRun).mockResolvedValue({
    ...record,
    runStatus: 6,
    resolutionRequestId: 'receipt',
    resolvedBy: '9007199254740997',
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('推送日志人工核查实际页面', () => {
  it('运行权限不能显示核查入口', async () => {
    harness.permissions = ['dataforge:data-push:run'];
    await mount();
    expect(button('核查处置')).toBeUndefined();
  });
  it('重新读取凭证、要求双确认与证据，并保持大 ID 和非成功结论', async () => {
    await mount();
    button('核查处置')!.click();
    await flush();
    expect(button('提交核查并停用排期')!.disabled).toBe(true);
    button('关闭核查')!.click();
    await flush();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    expect(api.resolveDataPushRun).toHaveBeenCalledWith(
      expect.objectContaining({
        recordId: '9007199254740995',
        requestId: expect.any(String),
        outcome: 'NO_EFFECT',
        confirmedStopped: true,
        outputReviewed: true,
        stoppedEvidenceRef: 'CASE-STOP-1',
        outputEvidenceRef: 'CASE-OUTPUT-1',
      }),
    );
    expect(harness.refreshed).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('9007199254740997');
  });
  it('回执丢失后重试同一请求号，改变结论时不另发命令', async () => {
    vi.mocked(api.resolveDataPushRun).mockRejectedValueOnce(
      new Error('lost ack'),
    );
    await mount();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    const first = vi.mocked(api.resolveDataPushRun).mock.calls[0]![0];
    await edit('核查原因', '改变内容');
    button('提交核查并停用排期')!.click();
    await flush();
    expect(api.resolveDataPushRun).toHaveBeenCalledTimes(1);
    expect(harness.warning).toHaveBeenCalledOnce();
    await edit('核查原因', '已核查全部批次');
    button('提交核查并停用排期')!.click();
    await flush();
    expect(vi.mocked(api.resolveDataPushRun).mock.calls[1]![0]).toEqual(first);
  });
  it('新详情已处置时不得继续核查旧列表记录', async () => {
    vi.mocked(api.getDataPushTaskLog).mockResolvedValue({
      ...record,
      runStatus: 6,
    });
    await mount();
    button('核查处置')!.click();
    await flush();
    expect(button('提交核查并停用排期')).toBeUndefined();
    expect(harness.warning).toHaveBeenCalledOnce();
  });
  it('关闭后旧详情响应不能回填或恢复核查表单', async () => {
    const pending = deferred<DataforgeDataPushTaskApi.TaskLog>();
    vi.mocked(api.getDataPushTaskLog).mockReturnValue(pending.promise);
    await mount();
    button('核查处置')!.click();
    await flush();
    button('关闭核查')!.click();
    await flush();
    pending.resolve(record);
    await flush();
    expect(host.querySelector('[aria-label="核查原因"]')).toBeNull();
    expect(api.resolveDataPushRun).not.toHaveBeenCalled();
  });
  it('切换登录会话使旧核查回执失效，不刷新另一租户的页面', async () => {
    const pending = deferred<DataforgeDataPushTaskApi.TaskLog>();
    vi.mocked(api.resolveDataPushRun).mockReturnValue(pending.promise);
    await mount();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    harness.access.refreshToken = 2;
    await flush();
    pending.resolve({ ...record, runStatus: 6 });
    await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.refreshed).not.toHaveBeenCalled();
    expect(host.querySelector('[aria-label="核查原因"]')).toBeNull();
  });
  it('非法证据编号不得提交，并且请求在途阻止重复点击', async () => {
    await mount();
    await ready();
    await edit('输出证据编号', 'https://invalid/?token=secret');
    expect(button('提交核查并停用排期')!.disabled).toBe(true);
    await edit('输出证据编号', 'CASE-OUTPUT-1');
    const pending = deferred<DataforgeDataPushTaskApi.TaskLog>();
    vi.mocked(api.resolveDataPushRun).mockReturnValue(pending.promise);
    button('提交核查并停用排期')!.click();
    await flush();
    expect(button('提交核查并停用排期')!.disabled).toBe(true);
    button('提交核查并停用排期')!.click();
    expect(api.resolveDataPushRun).toHaveBeenCalledOnce();
    pending.resolve({ ...record, runStatus: 6 });
    await flush();
  });
});
