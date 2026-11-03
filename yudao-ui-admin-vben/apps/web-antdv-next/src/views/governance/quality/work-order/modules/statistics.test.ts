import type { App } from 'vue';

import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getWorkOrderStatistics } from '#/api/governance/quality/workorder';

import WorkOrderPage from '../index.vue';
import Statistics from './statistics.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    permitted: true,
  },
  user: { userInfo: { userId: '9' } },
  renderers: [] as ReturnType<typeof vi.fn>[],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.access.permitted }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('@vben/plugins/echarts', () => ({
  EchartsUI: defineComponent({
    setup: () => () => h('div', { class: 'chart' }),
  }),
  useEcharts: () => {
    const render = vi.fn().mockResolvedValue(null);
    harness.renderers.push(render);
    return { renderEcharts: render };
  },
}));
vi.mock('antdv-next', () => ({
  message: {},
  Modal: {},
  Tag: 'span',
  TextArea: 'textarea',
  Segmented: defineComponent({
    props: ['value', 'options'],
    emits: ['update:value'],
    setup:
      (_, { emit }) =>
      () =>
        h(
          'nav',
          ['all', 'todo', 'stats'].map((tab) =>
            h(
              'button',
              { 'data-tab': tab, onClick: () => emit('update:value', tab) },
              tab,
            ),
          ),
        ),
  }),
  Alert: defineComponent({
    props: ['title'],
    setup: (props) => () => h('p', props.title),
  }),
  Empty: defineComponent({
    props: ['description'],
    setup: (props) => () => h('p', props.description),
  }),
  Spin: defineComponent({ setup: () => () => h('span', 'loading') }),
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Select: defineComponent({
    props: ['value', 'disabled', 'options'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            value: props.value,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit(
                'update:value',
                Number((event.target as HTMLSelectElement).value),
              ),
          },
          [7, 15, 30].map((value) => h('option', { value }, value)),
        ),
  }),
}));
vi.mock('#/api/governance/quality/workorder', () => ({
  getWorkOrderStatistics: vi.fn(),
  assignWorkOrder: vi.fn(),
  claimWorkOrder: vi.fn(),
  confirmWorkOrder: vi.fn(),
  deleteWorkOrder: vi.fn(),
  getMyTodoWorkOrderList: vi.fn(),
  getWorkOrderPage: vi.fn(),
  getWorkOrderRawIssues: vi.fn(),
  recheckWorkOrder: vi.fn(),
  submitWorkOrderResolution: vi.fn(),
  transferWorkOrderCommand: vi.fn(),
  withdrawWorkOrder: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', [slots.extra?.(), slots.default?.()]),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: 'div',
  useVbenVxeGrid: () => [
    defineComponent({ setup: () => () => h('div', 'grid') }),
    { query: vi.fn() },
  ],
}));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./form.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}));
vi.mock('./resolution.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}));

let app: App | undefined;
let host: HTMLDivElement;
function fixture(name = '责任人', count = 2): QualityWorkOrderApi.Statistics {
  return {
    asOf: 1_790_140_000_000,
    scope: 'authorized',
    window: 30,
    countUnit: 'WORK_ORDER',
    sampleStatus: 'SCORED',
    totalCount: count,
    ownerSummary: [
      { ownerUserId: '9223372036854775807', ownerUserName: name, count },
    ],
    statusSummary: { PENDING: count },
    trend: [{ date: '2026-09-23', createdCount: count, processedCount: 0 }],
    closeException: 0,
    closeExplained: 0,
    closeLegacy: 0,
    closeVerifiedPass: 0,
    currentOverdue: 0,
    coverage: '按当前可见范围统计，不等于规则通过率',
  };
}
function deferred() {
  let resolve!: (value: QualityWorkOrderApi.Statistics) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<QualityWorkOrderApi.Statistics>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(Statistics);
  app.mount(host);
}
function changeDays(value: number) {
  const select = host.querySelector('select')!;
  select.value = String(value);
  select.dispatchEvent(new Event('change', { bubbles: true }));
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.renderers = [];
  harness.access = reactive({
    accessToken: '1',
    refreshToken: undefined,
    permitted: true,
  });
  harness.user = reactive({ userInfo: { userId: '9' } });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('actual work-order statistics panel and charts', () => {
  it('the actual page opens the statistics panel and leaving the tab disposes its pending request', async () => {
    const pending = deferred();
    vi.mocked(getWorkOrderStatistics)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(fixture('重新打开'));
    app = createApp(WorkOrderPage);
    app.mount(host);
    expect(getWorkOrderStatistics).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-tab="stats"]')!.click();
    await flush();
    expect(getWorkOrderStatistics).toHaveBeenCalledTimes(1);
    host.querySelector<HTMLButtonElement>('[data-tab="all"]')!.click();
    await flush();
    pending.resolve(fixture('已离开的页面'));
    await flush();
    expect(host.textContent).not.toContain('已离开的页面');
    host.querySelector<HTMLButtonElement>('[data-tab="stats"]')!.click();
    await flush();
    expect(host.textContent).toContain('重新打开（9223372036854775807）');
    expect(getWorkOrderStatistics).toHaveBeenCalledTimes(2);
  });
  it('renders real owner counts, separate same-name identities, unassigned and lossless IDs', async () => {
    const stats = fixture('同名');
    stats.ownerSummary.push(
      { ownerUserId: 8, ownerUserName: '同名', count: 1 },
      { ownerUserId: null, ownerUserName: '未认责', count: 1 },
    );
    stats.totalCount = 4;
    vi.mocked(getWorkOrderStatistics).mockResolvedValue(stats);
    mount();
    await flush();
    expect(getWorkOrderStatistics).toHaveBeenCalledWith(30);
    expect(host.textContent).toContain('可见工单 4 条');
    expect(host.textContent).toContain('同名（9223372036854775807）：2 条');
    expect(host.textContent).toContain('同名（8）：1 条');
    expect(host.textContent).toContain('未认责：1 条');
    expect(harness.renderers[2]).toHaveBeenCalledWith(
      expect.objectContaining({
        xAxis: expect.objectContaining({
          data: ['同名（9223372036854775807）', '同名（8）', '未认责'],
        }),
        series: [expect.objectContaining({ data: [2, 1, 1] })],
        tooltip: expect.objectContaining({ renderMode: 'richText' }),
      }),
    );
  });

  it('uses explicit no-sample state instead of an empty owner chart', async () => {
    vi.mocked(getWorkOrderStatistics).mockResolvedValue({
      ...fixture(),
      sampleStatus: 'NO_SAMPLE',
      totalCount: 0,
      ownerSummary: [],
      statusSummary: {},
    });
    mount();
    await flush();
    expect(host.textContent).toContain('所选期间内没有当前用户可见的工单');
    expect(harness.renderers).toHaveLength(0);
  });

  it('does not request data without query permission', async () => {
    harness.access.permitted = false;
    mount();
    await flush();
    expect(getWorkOrderStatistics).not.toHaveBeenCalled();
    expect(host.textContent).toContain('无权查看工单统计');
  });

  it('shows server forbidden separately from empty data', async () => {
    vi.mocked(getWorkOrderStatistics).mockRejectedValue({
      response: { status: 403 },
    });
    mount();
    await flush();
    expect(host.textContent).toContain('无权查看工单统计');
    expect(host.textContent).not.toContain('没有当前用户可见');
  });

  it('keeps a failed response visible and permits retry', async () => {
    vi.mocked(getWorkOrderStatistics)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(fixture());
    mount();
    await flush();
    expect(host.textContent).toContain('统计加载失败');
    host.querySelector('button')!.click();
    await flush();
    expect(host.textContent).toContain('责任人（9223372036854775807）：2 条');
    expect(host.textContent).not.toContain('统计加载失败');
  });

  it('rejects the old server contract instead of fabricating empty owner statistics', async () => {
    const stats = fixture();
    Reflect.deleteProperty(stats, 'ownerSummary');
    vi.mocked(getWorkOrderStatistics).mockResolvedValue(stats);
    mount();
    await flush();
    expect(host.textContent).toContain('统计加载失败');
    expect(harness.renderers).toHaveLength(0);
  });

  it('retains latest days and loading state when responses complete out of order', async () => {
    const old = deferred();
    const current = deferred();
    vi.mocked(getWorkOrderStatistics)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    changeDays(7);
    await flush();
    old.resolve(fixture('旧期间'));
    await flush();
    expect(host.textContent).toContain('正在加载统计');
    expect(host.textContent).not.toContain('旧期间');
    current.resolve(fixture('新期间'));
    await flush();
    expect(host.textContent).toContain('新期间');
    expect(getWorkOrderStatistics).toHaveBeenLastCalledWith(7);
  });

  it('clears an already rendered session before refetching and discards stale failures', async () => {
    const old = deferred();
    const current = deferred();
    vi.mocked(getWorkOrderStatistics)
      .mockResolvedValueOnce(fixture('原租户'))
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    await flush();
    changeDays(7);
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    expect(host.textContent).not.toContain('原租户');
    current.resolve(fixture('现租户'));
    await flush();
    old.reject(new Error('late error'));
    await flush();
    expect(host.textContent).toContain('现租户');
    expect(host.textContent).not.toContain('统计加载失败');
  });

  it('discards a previous user response and permission revocation clears data', async () => {
    const old = deferred();
    const current = deferred();
    vi.mocked(getWorkOrderStatistics)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    harness.user.userInfo.userId = '10';
    await flush();
    old.resolve(fixture('旧用户'));
    await flush();
    expect(host.textContent).not.toContain('旧用户');
    current.resolve(fixture('当前用户'));
    await flush();
    harness.access.permitted = false;
    await flush();
    expect(host.textContent).not.toContain('当前用户（9223372036854775807）');
    expect(host.textContent).toContain('无权查看工单统计');
  });

  it('does not render a response after unmount', async () => {
    const pending = deferred();
    vi.mocked(getWorkOrderStatistics).mockReturnValue(pending.promise);
    mount();
    app!.unmount();
    app = undefined;
    pending.resolve(fixture('卸载后'));
    await flush();
    expect(harness.renderers).toHaveLength(0);
    expect(host.textContent).not.toContain('卸载后');
  });
});
