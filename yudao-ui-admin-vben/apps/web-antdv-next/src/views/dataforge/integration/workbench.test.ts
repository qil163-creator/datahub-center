/* eslint-disable vue/one-component-per-file -- 页面替身隔离运行环境，验证真实工作台的告警隐藏与统计呈现。 */
import type { App } from 'vue';

import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/integration';

import { defaultTask } from './model';
import Workbench from './workbench.vue';

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    accessToken: 'session',
    refreshToken: 'refresh',
    accessCodes: [],
  }),
  useUserStore: () => ({ userInfo: { userId: '9007199254740999' } }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup: (_props, { slots }) => () => h('div', slots.default?.()),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDate: () => '2026-11-02 12:00:00' }));
vi.mock('@vueuse/core', () => ({ useStorage: () => ref(true) }));
vi.mock('#/api/dataforge/integration', () => ({
  getIntegrationPage: vi.fn(),
  getIntegrationStatistics: vi.fn(),
  getIntegrationCategories: vi.fn(async () => []),
  getIntegrationOwners: vi.fn(async () => []),
  getIntegration: vi.fn(),
  cloneIntegration: vi.fn(),
  deleteIntegration: vi.fn(),
  runIntegration: vi.fn(),
  setIntegrationSchedule: vi.fn(),
  setIntegrationStatus: vi.fn(),
  stopIntegration: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup: (_props, { slots }) => () =>
      h('div', [slots.default?.(), slots.popupRender?.()]),
  });
  const Button = defineComponent({
    props: { disabled: Boolean, loading: Boolean },
    setup: (props, { slots }) => () =>
      h('button', { disabled: props.disabled || props.loading }, slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: {
        message: { type: String, default: '' },
        description: { type: String, default: '' },
      },
      setup: (props) => () => h('div', { role: 'alert' }, [props.message, props.description]),
    }),
    Button,
    MenuItem: Button,
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      setup: (props) => () =>
        h('button', { disabled: props.disabled, 'data-checked': String(props.checked) }),
    }),
    ConfigProvider: Container,
    DateRangePicker: Container,
    Dropdown: Container,
    Empty: Container,
    Input: Container,
    Menu: Container,
    Select: Container,
    Spin: Container,
    Tooltip: Container,
    Tree: Container,
    Modal: defineComponent({ setup: () => () => null }),
    theme: { defaultAlgorithm: {} },
    message: { info: vi.fn(), success: vi.fn() },
  };
});
const gridHarness = vi.hoisted(() => ({
  options: undefined as any,
}));
vi.mock('#/adapter/vxe-table', () => ({
  NativeVxeButton: defineComponent({
    props: { disabled: Boolean, loading: Boolean },
    setup: (props, { slots }) => () =>
      h('button', { disabled: props.disabled || props.loading }, slots.default?.()),
  }),
  useVbenVxeGrid: (options: any) => {
    gridHarness.options = options.gridOptions;
    const Grid = defineComponent({
      props: { tableData: { type: Array, default: () => [] }, tableTitle: { type: String, default: '' } },
      setup: (props, { slots }) => () => h('div', { class: 'qi-table-toolbar' }, [
        h('h2', { class: 'native-list-title' }, props.tableTitle),
        h('div', { class: 'qi-table-tools' }, slots['toolbar-tools']?.()),
        h('table', { class: 'qi-table' }, [h('tbody', props.tableData.map((row) =>
          h('tr', ['id', 'info', 'category', 'last', 'control', 'strategy', 'owner', 'actions'].map((name) =>
            h('td', slots[name]?.({ row }))))))]),
        slots.pager?.(),
      ]),
    });
    return [Grid, { grid: {}, setLoading: vi.fn() }];
  },
}));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/components/sidebar-collapse/index.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: defineComponent({
    props: { content: { type: String, default: '' } },
    setup: (props) => () => h('span', props.content),
  }),
}));
vi.mock('./modules/category-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/instances-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/schedule-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/task-detail.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/task-form.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));

let app: App | undefined;
let host: HTMLDivElement;
function task(overrides: Partial<Api.Task> = {}): Api.Task {
  return {
    ...defaultTask('OFFLINE'),
    id: '9007199254740993',
    revision: 2,
    name: '保留的离线任务',
    categoryId: '9007199254740995',
    ownerId: '9007199254740997',
    status: 1,
    scheduleStatus: 1,
    configurationStatus: 'COMPLETE',
    executable: true,
    importedSnapshot: false,
    lastRunStatus: 'IDLE',
    dsProjectCode: '9007199254741001',
    dsDefinitionCode: '9007199254741003',
    ...overrides,
  };
}
async function mount(mode: Api.Mode = 'OFFLINE') {
  app = createApp(Workbench, { mode });
  app.mount(host);
  for (let count = 0; count < 8; count++) {
    await Promise.resolve();
    await nextTick();
  }
}
function statisticValues() {
  return [...host.querySelectorAll('.qi-statistics b')].map((element) => element.textContent);
}
beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getIntegrationPage).mockResolvedValue({ list: [task()], total: 1 });
  vi.mocked(api.getIntegrationStatistics).mockResolvedValue({
    running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0,
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('集成工作台工具栏', () => {
  it('复用项目 Grid 工具栏，并启用真实表格的刷新、全屏与列设置', async () => {
    await mount();
    expect(host.querySelector('.native-list-title')?.textContent).toBe('离线任务列表');
    expect(host.querySelector('.qi-table-tools')?.textContent).toContain('新增');
    expect(gridHarness.options.toolbarConfig.zoom).toBe(true);
    expect(gridHarness.options.toolbarConfig.custom).toBe(true);
    expect(gridHarness.options.columns.map((column: { field: string }) => column.field)).toEqual([
      'id', 'name', 'categoryName', 'lastRunStatus', 'status', 'executionStrategy', 'ownerName', 'actions',
    ]);
    const previousLoads = vi.mocked(api.getIntegrationPage).mock.calls.length;
    await gridHarness.options.toolbarConfig.refreshOptions.queryMethod();
    expect(vi.mocked(api.getIntegrationPage).mock.calls.length).toBe(previousLoads + 1);
  });

  it('保留搜索显隐、展开筛选和刷新行为', async () => {
    await mount();
    const button = (label: string) => host.querySelector<HTMLButtonElement>(`[aria-label="${label}"]`);
    expect(host.querySelector('.qi-filters')).not.toBeNull();
    button('显示或隐藏搜索')?.click();
    await nextTick();
    expect(host.querySelector('.qi-filters')).toBeNull();
    button('显示或隐藏搜索')?.click();
    await nextTick();
    const expand = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === '展开');
    expand?.click();
    await nextTick();
    expect(host.querySelector('[aria-label="筛选责任人"]')).not.toBeNull();
    for (let count = 0; count < 8; count++) {
      await Promise.resolve();
      await nextTick();
    }

  });

  it('实时任务不显示策略与调度列', async () => {
    await mount('REALTIME');
    expect(host.querySelector('.native-list-title')?.textContent).toBe('实时任务列表');
    expect(gridHarness.options.columns.map((column: { field: string }) => column.field))
      .not.toContain('executionStrategy');
  });
});

describe('集成工作台运行环境诊断', () => {
  it('缺失调度对象时隐藏行内告警，保留任务与上线状态并停止执行入口', async () => {
    const runtimeMessage = 'DolphinScheduler 项目 9007199254741001 不存在，请检查本地调度元数据。';
    vi.mocked(api.getIntegrationPage).mockResolvedValue({
      list: [task({ runtimeAvailable: false, runtimeMessage, executable: false })], total: 1,
    });
    await mount();
    const row = host.querySelector('.qi-table tbody tr');
    expect(row?.textContent).toContain('9007199254740993');
    expect(row?.textContent).toContain('保留的离线任务');
    expect(row?.querySelector('[role="alert"]')).toBeNull();
    expect(row?.textContent).not.toContain(runtimeMessage);
    expect(row?.querySelector<HTMLButtonElement>('[aria-label="保留的离线任务任务状态"]')?.dataset.checked).toBe('true');
    expect(row?.querySelector<HTMLButtonElement>('[aria-label="保留的离线任务调度状态"]')?.dataset.checked).toBe('true');
    const run = [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '执行一次');
    expect(run?.disabled).toBe(true);
    run?.click();
    expect(api.runIntegration).not.toHaveBeenCalled();
  });

  it('不完整统计即使返回零也显示不可用，但不展示统计告警', async () => {
    const runtimeMessage = '部分任务绑定的 DolphinScheduler 项目已丢失，统计不完整。';
    vi.mocked(api.getIntegrationStatistics).mockResolvedValue({
      running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0,
      runtimeAvailable: false, runtimeMessage,
    });
    await mount();
    expect(statisticValues()).toEqual(['不可用', '不可用', '不可用', '不可用']);
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).not.toContain('运行统计不完整');
    expect(host.textContent).not.toContain(runtimeMessage);
    expect([...host.querySelectorAll<HTMLButtonElement>('.qi-statistics > button')].every((button) => button.disabled)).toBe(true);
  });

  it.each([undefined, true])('运行环境状态为 %s 时兼容原有准确零统计', async (runtimeAvailable) => {
    vi.mocked(api.getIntegrationStatistics).mockResolvedValue({
      running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0, runtimeAvailable,
    });
    await mount();
    expect(statisticValues()).toEqual(['0次', '0次', '0次', '0%']);
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect([...host.querySelectorAll<HTMLButtonElement>('.qi-statistics > button')].some((button) => button.disabled)).toBe(false);
  });
});
