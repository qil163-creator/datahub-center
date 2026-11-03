import type { App, SetupContext } from 'vue';

import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/masterdistribute';

import Log from './log.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: number; refreshToken?: number },
  options: {} as { onOpenChange: (open: boolean) => Promise<void> },
  permissions: ['governance:master-distribute:resolve'],
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
        getData: () => ({ id: '9007199254740993', name: '主数据派发' }),
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
          dataSource?: GovernanceMasterDistributeApi.DistributeLog[];
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
vi.mock('#/api/governance/masterdistribute', () => ({
  getMasterDistributeLog: vi.fn(),
  getMasterDistributeLogPage: vi.fn(),
  resolveMasterDistributeRun: vi.fn(),
  stopMasterDistributeRun: vi.fn(),
}));
vi.mock('../data', () => ({
  RUN_STATUS: { WAITING: 1, RUNNING: 2, UNKNOWN: 5, RESOLVED: 6 },
  RUN_STATUS_OPTIONS: [],
  RUN_TYPE_OPTIONS: [],
  optionColor: () => 'default',
  optionLabel: (_: unknown, value: unknown) => String(value),
}));
const record: GovernanceMasterDistributeApi.DistributeLog = {
  id: '9007199254740995',
  distributeId: '9007199254740993',
  runStatus: 5,
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
  harness.permissions = ['governance:master-distribute:resolve'];
  vi.mocked(api.getMasterDistributeLogPage).mockResolvedValue({
    list: [record],
    total: 1,
  });
  vi.mocked(api.getMasterDistributeLog).mockResolvedValue({ ...record });
  vi.mocked(api.resolveMasterDistributeRun).mockResolvedValue({
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

describe('按运行编号独立停止', () => {
  const active = { ...record, runStatus: 2 };
  const receipt = {
    ...record,
    runStatus: 5,
    stopRequestedBy: '9007199254740997',
    stopRequestedAt: 1_790_488_800_000,
    stopReason: '维护停止',
  };
  beforeEach(() => {
    harness.permissions = ['governance:master-distribute:run'];
    vi.mocked(api.getMasterDistributeLogPage).mockResolvedValue({
      list: [active],
      total: 1,
    });
    vi.mocked(api.getMasterDistributeLog).mockResolvedValue(active);
    vi.mocked(api.stopMasterDistributeRun).mockResolvedValue(receipt);
  });
  async function prepare() {
    await mount();
    button('停止本次运行')!.click();
    await flush();
    await edit('停止原因', '维护停止');
  }
  it('提交原因和指定大运行编号，显示审计并刷新父页面', async () => {
    await prepare();
    button('确认停止本次运行')!.click();
    await flush();
    expect(api.stopMasterDistributeRun).toHaveBeenCalledWith({
      recordId: record.id,
      reason: '维护停止',
    });
    expect(harness.refreshed).toHaveBeenCalledOnce();
    expect(host.textContent).toContain(receipt.stopRequestedBy);
    expect(host.textContent).toContain('维护停止');
    expect(api.resolveMasterDistributeRun).not.toHaveBeenCalled();
  });
  it.each([
    'governance:master-distribute:release',
    'governance:master-distribute:resolve',
    'governance:master-distribute:query',
  ])('只有 %s 不能显示停止入口', async (permission) => {
    harness.permissions = [permission];
    await mount();
    expect(button('停止本次运行')).toBeUndefined();
  });
  it.each([3, 4, 5, 6])('终态 %s 不再提供停止', async (runStatus) => {
    vi.mocked(api.getMasterDistributeLogPage).mockResolvedValue({
      list: [{ ...record, runStatus }],
      total: 1,
    });
    await mount();
    expect(button('停止本次运行')).toBeUndefined();
  });
  it.each([
    { ...active, id: '999' },
    { ...active, distributeId: '999' },
    { ...active, runStatus: 3 },
  ])('新详情身份或状态变化时禁止停止，不定位到新运行', async (fresh) => {
    vi.mocked(api.getMasterDistributeLog).mockResolvedValue(fresh);
    await mount();
    button('停止本次运行')!.click();
    await flush();
    expect(button('确认停止本次运行')).toBeUndefined();
    expect(api.stopMasterDistributeRun).not.toHaveBeenCalled();
  });
  it('拒绝空白和超长原因，在途禁止重复提交', async () => {
    await prepare();
    await edit('停止原因', ' ');
    expect(button('确认停止本次运行')!.disabled).toBe(true);
    await edit('停止原因', 'x'.repeat(501));
    expect(button('确认停止本次运行')!.disabled).toBe(true);
    await edit('停止原因', '维护停止');
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.stopMasterDistributeRun).mockReturnValueOnce(pending.promise);
    button('确认停止本次运行')!.click();
    await flush();
    expect(button('确认停止本次运行')!.disabled).toBe(true);
    button('确认停止本次运行')!.click();
    expect(api.stopMasterDistributeRun).toHaveBeenCalledOnce();
    pending.resolve(receipt);
    await flush();
  });
  it('回执丢失后保持原运行和原原因重试，禁止变更审计', async () => {
    vi.mocked(api.stopMasterDistributeRun).mockRejectedValueOnce(
      new Error('lost ack'),
    );
    await prepare();
    button('确认停止本次运行')!.click();
    await flush();
    await edit('停止原因', '另一原因');
    button('确认停止本次运行')!.click();
    await flush();
    expect(api.stopMasterDistributeRun).toHaveBeenCalledTimes(1);
    await edit('停止原因', '维护停止');
    button('确认停止本次运行')!.click();
    await flush();
    expect(vi.mocked(api.stopMasterDistributeRun).mock.calls[1]).toEqual(
      vi.mocked(api.stopMasterDistributeRun).mock.calls[0],
    );
    expect(harness.success).toHaveBeenCalledOnce();
  });
  it('旧详情不能覆盖停止回执', async () => {
    await mount();
    const old = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.getMasterDistributeLog).mockReturnValueOnce(old.promise);
    button('日志')!.click();
    await flush();
    button('停止本次运行')!.click();
    await flush();
    await edit('停止原因', '维护停止');
    button('确认停止本次运行')!.click();
    await flush();
    old.resolve(active);
    await flush();
    expect(host.textContent).toContain(receipt.stopRequestedBy);
  });
  it.each(['关闭', '切换登录会话', '卸载'])(
    '%s 后迟到停止回执不能污染页面',
    async (action) => {
      const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
      vi.mocked(api.stopMasterDistributeRun).mockReturnValueOnce(
        pending.promise,
      );
      await prepare();
      button('确认停止本次运行')!.click();
      await flush();
      if (action === '关闭') await harness.options.onOpenChange(false);
      else if (action === '切换登录会话') harness.access.refreshToken = 2;
      else {
        app?.unmount();
        app = undefined;
      }
      await flush();
      const refreshCount = harness.refreshed.mock.calls.length;
      pending.resolve(receipt);
      await flush();
      expect(harness.refreshed).toHaveBeenCalledTimes(refreshCount);
      expect(refreshCount).toBe(action === '关闭' ? 1 : 0);
      expect(harness.success).not.toHaveBeenCalled();
    },
  );
  it('关闭停止窗口后迟到详情不能恢复表单', async () => {
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.getMasterDistributeLog).mockReturnValueOnce(pending.promise);
    await mount();
    button('停止本次运行')!.click();
    await flush();
    button('关闭核查')!.click();
    await flush();
    pending.resolve(active);
    await flush();
    expect(button('确认停止本次运行')).toBeUndefined();
  });
  it('回执归属不符时不能提示成功', async () => {
    vi.mocked(api.stopMasterDistributeRun).mockResolvedValue({
      ...receipt,
      id: '999',
    });
    await prepare();
    button('确认停止本次运行')!.click();
    await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.refreshed).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalledOnce();
  });
});

describe('执行记录同步父列表', () => {
  it('关闭执行记录重新获取父列表状态', async () => {
    await mount();
    await harness.options.onOpenChange(false);
    expect(harness.refreshed).toHaveBeenCalledOnce();
  });
  it('轮询发现自然结束后通知父列表，仅通知一次', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(api.getMasterDistributeLogPage)
        .mockResolvedValueOnce({
          list: [{ ...record, runStatus: 2 }],
          total: 1,
        })
        .mockResolvedValue({ list: [{ ...record, runStatus: 3 }], total: 1 });
      await mount();
      await vi.advanceTimersByTimeAsync(5000);
      await flush();
      expect(harness.refreshed).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(10_000);
      expect(harness.refreshed).toHaveBeenCalledOnce();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('主数据派发日志人工核查实际页面', () => {
  it('运行权限可独立停止未发布任务的具体运行', async () => {
    harness.permissions = ['governance:master-distribute:run'];
    vi.mocked(api.getMasterDistributeLogPage).mockResolvedValue({
      list: [{ ...record, runStatus: 1 }],
      total: 1,
    });
    await mount();
    expect(button('停止本次运行')).toBeDefined();
  });
  it('列表刷新逆序返回时只有最新请求可以回填', async () => {
    await mount();
    const old = deferred<{
      list: GovernanceMasterDistributeApi.DistributeLog[];
      total: number;
    }>();
    vi.mocked(api.getMasterDistributeLogPage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ list: [{ ...record, runStatus: 3 }], total: 1 });
    button('刷新')!.click();
    await flush();
    button('刷新')!.click();
    await flush();
    expect(button('核查处置')).toBeUndefined();
    old.resolve({ list: [record], total: 1 });
    await flush();
    expect(button('核查处置')).toBeUndefined();
  });
  it('关闭再打开后旧列表响应不能污染新会话', async () => {
    await mount();
    const old = deferred<{
      list: GovernanceMasterDistributeApi.DistributeLog[];
      total: number;
    }>();
    vi.mocked(api.getMasterDistributeLogPage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ list: [], total: 0 });
    button('刷新')!.click();
    await flush();
    await harness.options.onOpenChange(false);
    await harness.options.onOpenChange(true);
    old.resolve({ list: [record], total: 1 });
    await flush();
    expect(button('核查处置')).toBeUndefined();
  });
  it('详情归属不匹配时不得提交核查', async () => {
    vi.mocked(api.getMasterDistributeLog).mockResolvedValue({
      ...record,
      distributeId: '999',
    });
    await mount();
    button('核查处置')!.click();
    await flush();
    expect(button('提交核查并停用排期')).toBeUndefined();
    expect(api.resolveMasterDistributeRun).not.toHaveBeenCalled();
  });
  it('处置回执返回后旧日志详情不得把审计覆盖回未知', async () => {
    await mount();
    const old = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.getMasterDistributeLog).mockReturnValueOnce(old.promise);
    button('日志')!.click();
    await flush();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    expect(host.textContent).toContain('receipt');
    old.resolve(record);
    await flush();
    expect(host.textContent).toContain('receipt');
  });
  it('卸载后迟到核查回执不提示成功或触发父页刷新', async () => {
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.resolveMasterDistributeRun).mockReturnValue(pending.promise);
    await mount();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    app?.unmount();
    app = undefined;
    pending.resolve({ ...record, runStatus: 6 });
    await flush();
    expect(harness.refreshed).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
  });
  it('运行权限不能显示核查入口', async () => {
    harness.permissions = ['governance:master-distribute:run'];
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
    expect(api.resolveMasterDistributeRun).toHaveBeenCalledWith(
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
    vi.mocked(api.resolveMasterDistributeRun).mockRejectedValueOnce(
      new Error('lost ack'),
    );
    await mount();
    await ready();
    button('提交核查并停用排期')!.click();
    await flush();
    const first = vi.mocked(api.resolveMasterDistributeRun).mock.calls[0]![0];
    await edit('核查原因', '改变内容');
    button('提交核查并停用排期')!.click();
    await flush();
    expect(api.resolveMasterDistributeRun).toHaveBeenCalledTimes(1);
    expect(harness.warning).toHaveBeenCalledOnce();
    await edit('核查原因', '已核查全部批次');
    button('提交核查并停用排期')!.click();
    await flush();
    expect(vi.mocked(api.resolveMasterDistributeRun).mock.calls[1]![0]).toEqual(
      first,
    );
  });
  it('新详情已处置时不得继续核查旧列表记录', async () => {
    vi.mocked(api.getMasterDistributeLog).mockResolvedValue({
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
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.getMasterDistributeLog).mockReturnValue(pending.promise);
    await mount();
    button('核查处置')!.click();
    await flush();
    button('关闭核查')!.click();
    await flush();
    pending.resolve(record);
    await flush();
    expect(host.querySelector('[aria-label="核查原因"]')).toBeNull();
    expect(api.resolveMasterDistributeRun).not.toHaveBeenCalled();
  });
  it('切换登录会话使旧核查回执失效，不刷新另一租户的页面', async () => {
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.resolveMasterDistributeRun).mockReturnValue(pending.promise);
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
    const pending = deferred<GovernanceMasterDistributeApi.DistributeLog>();
    vi.mocked(api.resolveMasterDistributeRun).mockReturnValue(pending.promise);
    button('提交核查并停用排期')!.click();
    await flush();
    expect(button('提交核查并停用排期')!.disabled).toBe(true);
    button('提交核查并停用排期')!.click();
    expect(api.resolveMasterDistributeRun).toHaveBeenCalledOnce();
    pending.resolve({ ...record, runStatus: 6 });
    await flush();
  });
});
