/* eslint-disable vue/one-component-per-file -- 隔离外部控件，验证真实研发页面的权限、模式和保存交互。 */
import type { App, PropType, VNodeChild } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, onMounted, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DevelopmentTasks from './index.vue';
import Workbench from './workbench.vue';

const harness = vi.hoisted(() => ({
  permissions: new Set<string>(),
  getTask: vi.fn(),
  getList: vi.fn(),
  getPage: vi.fn(),
  getStatistics: vi.fn(),
  createTask: vi.fn(),
  updateTask: vi.fn(),
  runTask: vi.fn(),
  updateStatus: vi.fn(),
  infoOpen: vi.fn(),
  instanceOpen: vi.fn(),
  formOpen: vi.fn(),
  push: vi.fn(),
  tabAdd: undefined as (() => void) | undefined,
  tabClose: undefined as ((key: string) => void) | undefined,
  infoSaved: undefined as
    | ((id: string, configure: boolean) => void)
    | undefined,
  columns: [] as { title?: string }[],
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.push }),
  onBeforeRouteLeave: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.has(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({
  formatDate: String,
  formatDateTime: String,
  formatPast: String,
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevDirList: vi.fn(async () => [
    { id: '7', parentId: '0', name: '测试类目' },
  ]),
  getDevTask: harness.getTask,
  getDevTaskListByDir: harness.getList,
  getDevTaskPage: harness.getPage,
  getDevTaskStatistics: harness.getStatistics,
  createDevTask: harness.createTask,
  updateDevTask: harness.updateTask,
  runDevTask: harness.runTask,
  updateDevTaskStatus: harness.updateStatus,
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
  getDatabaseTableList: vi.fn(async () => []),
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => []),
}));
vi.mock('#/components/monaco/setup', () => ({
  resolveSqlLanguage: () => 'sql',
  setColumnLoader: vi.fn(),
  setCompletionTables: vi.fn(),
}));
vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    props: {
      modelValue: { type: String, default: undefined },
      readonly: Boolean,
    },
    emits: ['save', 'update:modelValue'],
    setup: (props, { emit, expose }) => {
      expose({ format: vi.fn() });
      return () =>
        h('textarea', {
          'data-editor': '',
          readOnly: props.readonly,
          value: props.modelValue,
          onInput: (event: Event) =>
            emit(
              'update:modelValue',
              (event.target as HTMLTextAreaElement).value,
            ),
          onKeydown: (event: KeyboardEvent) => {
            if (event.ctrlKey && event.key === 's') emit('save');
          },
        });
    },
  }),
}));
vi.mock('#/components/monaco/log-viewer.vue', () => ({ default: () => null }));
vi.mock('#/components/tree-toolbar/index.vue', () => ({ default: () => null }));
vi.mock('../execution/use-run-commands', () => ({
  useRunCommands: () => ({
    busy: ref(false),
    execute: async (
      _key: string,
      payload: object,
      send: (command: object) => Promise<unknown>,
    ) => send({ ...payload, requestId: 'test-request' }),
  }),
}));
vi.mock('./modules/task-info.vue', () => ({
  default: defineComponent({
    props: { workloadMode: { type: String, default: undefined } },
    emits: ['saved'],
    setup: (_, { expose, emit }) => {
      expose({ open: harness.infoOpen, close: vi.fn() });
      harness.infoSaved = (id, configure) => emit('saved', id, configure);
      return () => null;
    },
  }),
}));
vi.mock('./modules/task-form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.formOpen, close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/dir-form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn(), close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/instance-modal.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.instanceOpen, close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/schedule-form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn(), close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/version-modal.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn(), close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/workbench-execution-panel.vue', () => ({
  default: defineComponent({
    props: { mode: { type: String, default: undefined }, recordId: { type: String, default: undefined }, taskId: { type: String, default: undefined } },
    setup: (props) => () =>
      h(
        'section',
        {
          'data-execution-panel': '',
          'data-mode': props.mode,
          'data-record-id': props.recordId,
          'data-task-id': props.taskId,
        },
        props.mode,
      ),
  }),
}));
vi.mock('./modules/WorkbenchPropertiesPanel.vue', () => ({
  default: defineComponent({
    props: { readonly: Boolean },
    setup: (props) => () =>
      h('section', { 'data-properties': '', 'data-readonly': props.readonly }),
  }),
}));
vi.mock('./modules/WorkbenchLineagePanel.vue', () => ({
  default: () => h('section', { 'data-lineage': '' }),
}));
vi.mock('./modules/program-config-fields.vue', () => ({ default: () => null }));
vi.mock('./modules/execution-config-fields.vue', () => ({
  default: () => null,
}));
vi.mock('./modules/task-detail-view.vue', () => ({ default: () => null }));
vi.mock('./modules/task-type-icon.vue', () => ({ default: () => null }));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  useVbenVxeGrid: (options: {
    gridOptions: {
      columns: { slots?: { default?: string }; title?: string }[];
      proxyConfig: {
        ajax: {
          query: (
            context: unknown,
          ) => Promise<{ list: DataforgeDevApi.Task[] }>;
        };
      };
    };
  }) => {
    harness.columns = options.gridOptions.columns;
    const rows = ref<DataforgeDevApi.Task[]>([]);
    const query = async () => {
      const result = await options.gridOptions.proxyConfig.ajax.query({
        page: { currentPage: 1, pageSize: 10 },
      });
      rows.value = result.list;
    };
    const Grid = defineComponent({
      setup: (_, { slots }) => {
        onMounted(() => void query());
        return () =>
          h('div', [
            slots['toolbar-actions']?.(),
            slots['toolbar-tools']?.(),
            ...rows.value.flatMap((row) =>
              options.gridOptions.columns.map((column) =>
                column.slots?.default
                  ? slots[column.slots.default]?.({ row })
                  : null,
              ),
            ),
          ]);
      },
    });
    return [Grid, { query, reload: query }];
  },
}));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const button = defineComponent({
    props: { disabled: Boolean },
    emits: ['click'],
    setup:
      (props, { slots, emit, attrs }) =>
      () =>
        h(
          'button',
          { ...attrs, disabled: props.disabled, onClick: () => emit('click') },
          [slots.icon?.(), slots.default?.()],
        ),
  });
  const menu = Object.assign(slot, { Item: button });
  return {
    Alert: slot,
    Button: button,
    Card: slot,
    DateRangePicker: slot,
    Empty: slot,
    Form: slot,
    Input: slot,
    InputNumber: slot,
    Menu: menu,
    Select: slot,
    Splitter: slot,
    SplitterPanel: slot,
    Switch: slot,
    Tag: slot,
    TabPane: slot,
    Tooltip: slot,
    TreeSelect: slot,
    Dropdown: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', [slots.default?.(), slots.popupRender?.()]),
    }),
    Tree: defineComponent({
      props: {
        treeData: {
          type: Array as PropType<{ children?: unknown[] }[]>,
          default: () => [],
        },
      },
      setup:
        (props, { slots }) =>
        () => {
          const render = (items: unknown[]): VNodeChild[] =>
            items.flatMap((item) => {
              const node = item as { children?: unknown[] };
              return [
                slots.titleRender?.(node),
                ...render(node.children ?? []),
              ];
            });
          return h('div', render(props.treeData ?? []));
        },
    }),
    Tabs: defineComponent({
      props: { hideAdd: Boolean },
      emits: ['edit'],
      setup: (props, { slots, emit }) => {
        harness.tabAdd = () => emit('edit', undefined, 'add');
        harness.tabClose = (key) => emit('edit', key, 'remove');
        return () =>
          h('div', [
            !props.hideAdd &&
              h('button', { 'data-tab-add': '', onClick: harness.tabAdd }, '+'),
            slots.default?.(),
          ]);
      },
    }),
    Modal: Object.assign(
      defineComponent({
        props: { open: Boolean },
        setup:
          (props, { slots }) =>
          () =>
            props.open ? h('div', [slots.default?.(), slots.footer?.()]) : null,
      }),
      { confirm: vi.fn(() => ({ destroy: vi.fn() })) },
    ),
    message: { success: vi.fn(), warning: vi.fn(), info: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let count = 0; count < 20; count++) await Promise.resolve();
  await nextTick();
}
function currentTask(id = '9007199254740993'): DataforgeDevApi.Task {
  return {
    id,
    name: `测试任务${id}`,
    dirId: '7',
    taskType: 'FLINK_SQL',
    content: 'SELECT 1',
    status: 0,
    programConfig: { workloadMode: 'STREAMING' },
    revision: 1,
  };
}
async function mount(
  component: typeof DevelopmentTasks | typeof Workbench,
  props: Record<string, unknown>,
) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(component, props);
  app.directive('access', () => {});
  app.mount(host);
  await flush();
}
function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (element) => element.textContent?.trim() === text,
  );
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.permissions.clear();
  ['query', 'save', 'run', 'execute-native', 'delete'].forEach((action) =>
    harness.permissions.add(`dataforge:dev-task:${action}`),
  );
  harness.instanceOpen.mockResolvedValue(undefined);
  harness.runTask.mockResolvedValue('9007199254740997');
  harness.getTask.mockImplementation(async (id: string) => currentTask(id));
  harness.getList.mockResolvedValue([currentTask()]);
  harness.getPage.mockResolvedValue({ list: [currentTask()], total: 1 });
  harness.getStatistics.mockResolvedValue({
    runningTasks: 0,
    todayFailed: 0,
    todayExecutions: 0,
    todaySuccessRate: 0,
  });
  harness.columns = [];
  harness.infoSaved = undefined;
  harness.tabAdd = undefined;
  harness.tabClose = undefined;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.useRealTimers();
});

describe('研发页面模式和交互边界', () => {
  it('资源树可独立折叠，右侧三按钮和底部三个面板互不挤占且查询空态不可导出', async () => {
    await mount(Workbench, {
      taskId: '9007199254740993',
      workloadMode: 'STREAMING',
    });
    host
      .querySelector<HTMLButtonElement>('[aria-label="切换资源目录"]')
      ?.click();
    await flush();
    expect(
      host.querySelector<HTMLElement>('.dev-workbench__aside')?.style.display,
    ).toBe('none');
    expect(host.querySelectorAll('.dev-workbench__strip button')).toHaveLength(
      3,
    );
    host
      .querySelector<HTMLButtonElement>('[aria-label="切换属性配置"]')
      ?.click();
    await flush();
    expect(host.querySelector('[data-properties]')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('[aria-label="血缘维护"]')?.click();
    await flush();
    expect(host.querySelector('[data-properties]')).toBeNull();
    expect(host.querySelector('[data-lineage]')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('[aria-label="执行历史"]')?.click();
    await flush();
    expect(
      host.querySelector<HTMLElement>('[data-execution-panel]')?.dataset.mode,
    ).toBe('history');
    host.querySelector<HTMLButtonElement>('[aria-label="查询结果"]')?.click();
    await flush();
    expect(
      host.querySelector('[aria-label="查询结果"].dev-workbench__results')
        ?.textContent,
    ).toContain('暂无数据');
    expect(button('↓ 导出')?.disabled).toBe(true);
    host
      .querySelector<HTMLButtonElement>('[aria-label="切换日志控制台"]')
      ?.click();
    await flush();
    expect(
      host.querySelector<HTMLElement>('[data-execution-panel]')?.dataset.mode,
    ).toBe('log');
  });
  it('已上线锁定定义编辑但保留运行和显式下线管理', async () => {
    const task = {
      ...currentTask(),
      status: 1,
      currentVersionId: '9007199254740995',
      executionCapability: 'NATIVE' as const,
    };
    harness.getTask.mockResolvedValue(task);
    await mount(Workbench, { taskId: task.id, workloadMode: 'STREAMING' });
    expect(
      host.querySelector<HTMLTextAreaElement>('[data-editor]')?.readOnly,
    ).toBe(true);
    expect(button('保存')?.disabled).toBe(true);
    expect(button('运行')?.disabled).toBe(false);
    host
      .querySelector<HTMLButtonElement>('[aria-label="切换属性配置"]')
      ?.click();
    await flush();
    expect(
      host.querySelector<HTMLElement>('[data-properties]')?.dataset.readonly,
    ).toBe('true');
    host
      .querySelector<HTMLTextAreaElement>('[data-editor]')
      ?.dispatchEvent(
        new KeyboardEvent('keydown', { key: 's', ctrlKey: true }),
      );
    await flush();
    expect(harness.updateTask).not.toHaveBeenCalled();
    harness.getTask.mockResolvedValue({ ...task, status: 0 });
    host
      .querySelector<HTMLButtonElement>('.dev-workbench__status-control button')
      ?.click();
    await flush();
    expect(harness.updateStatus).toHaveBeenCalledWith(task.id, 0);
    expect(
      host.querySelector<HTMLTextAreaElement>('[data-editor]')?.readOnly,
    ).toBe(false);
    expect(button('保存')?.disabled).toBe(false);
  });
  it('关闭最后任务页签后仍可返回任务列表', async () => {
    const onBack = vi.fn();
    await mount(Workbench, {
      taskId: '9007199254740993',
      workloadMode: 'STREAMING',
      readOnly: true,
      onBack,
    });
    harness.tabClose?.('task-9007199254740993');
    await flush();
    expect(host.querySelector('[data-editor]')).toBeNull();
    const back = button('返回列表');
    expect(back).toBeDefined();
    expect(back?.disabled).toBe(false);
    back?.click();
    await flush();
    expect(onBack).toHaveBeenCalledOnce();
  });
  it.each([
    ['FLINK_SQL', 'BATCH', 'SQL', 'Flink批'],
    ['SPARK_JAR', 'STREAMING', 'JAR', 'Spark流'],
    ['PYSPARK', 'STREAMING', 'Python', 'Spark流'],
  ] as const)(
    '%s 状态栏显示真实程序类型与引擎模式',
    async (taskType, workloadMode, programLabel, runtimeLabel) => {
      harness.getTask.mockResolvedValue({
        ...currentTask(),
        taskType,
        programConfig: { workloadMode },
      });
      await mount(Workbench, {
        taskId: '9007199254740993',
        workloadMode,
        readOnly: true,
      });
      const labels = [
        ...host.querySelectorAll('.dev-workbench__statusbar span'),
      ].map((element) => element.textContent?.trim());
      expect(labels).toContain(programLabel);
      expect(labels).toContain(runtimeLabel);
      expect(labels).not.toContain('Flink流');
    },
  );
  it('只读详情即使用户有保存权限也不能创建、打开编辑配置或快捷保存', async () => {
    await mount(Workbench, {
      taskId: '9007199254740993',
      workloadMode: 'STREAMING',
      readOnly: true,
    });
    expect(harness.getList).toHaveBeenCalledWith(undefined, 'STREAMING');
    expect(
      host.querySelector<HTMLTextAreaElement>('[data-editor]')?.readOnly,
    ).toBe(true);
    expect(host.querySelector('[data-tab-add]')).toBeNull();
    expect(button('依赖与验收配置')).toBeUndefined();
    harness.tabAdd?.();
    host
      .querySelector<HTMLTextAreaElement>('[data-editor]')
      ?.dispatchEvent(
        new KeyboardEvent('keydown', { key: 's', ctrlKey: true }),
      );
    await flush();
    expect(harness.infoOpen).not.toHaveBeenCalled();
    expect(harness.formOpen).not.toHaveBeenCalled();
    expect(harness.createTask).not.toHaveBeenCalled();
    expect(harness.updateTask).not.toHaveBeenCalled();
    expect(harness.runTask).not.toHaveBeenCalled();
  });
  it('运行已受理后留在编辑器展开内嵌日志，重复点击不重提任务', async () => {
    const task = {
      ...currentTask(),
      status: 1,
      currentVersionId: '9007199254740995',
      executionCapability: 'NATIVE' as const,
    };
    harness.getTask.mockResolvedValue(task);
    harness.instanceOpen.mockRejectedValueOnce(new Error('日志读取失败'));
    await mount(Workbench, { taskId: task.id, workloadMode: 'STREAMING' });
    button('运行')?.click();
    await flush();
    expect(harness.runTask).toHaveBeenCalledOnce();
    expect(button('运行')?.disabled).toBe(true);
    button('运行')?.click();
    await flush();
    expect(harness.runTask).toHaveBeenCalledOnce();
    expect(harness.instanceOpen).not.toHaveBeenCalled();
    expect(
      host.querySelector<HTMLElement>('[data-execution-panel]')?.dataset.mode,
    ).toBe('log');
    expect(
      host.querySelector<HTMLElement>('[data-execution-panel]')?.dataset.recordId,
    ).toBe('9007199254740997');
    expect(harness.push).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('提交结果待确认');
    expect(host.querySelector('[data-editor]')).not.toBeNull();
  });
  it('活动实例自动刷新到终态后停止轮询并保留本地编辑', async () => {
    vi.useFakeTimers();
    const running = {
      ...currentTask(),
      latestRecord: {
        id: '9007199254740997',
        status: 'RUNNING',
        log: '运行中',
      },
    };
    harness.getTask.mockResolvedValue(running);
    await mount(Workbench, { taskId: running.id, workloadMode: 'STREAMING' });
    const editor = host.querySelector<HTMLTextAreaElement>('[data-editor]')!;
    editor.value = 'SELECT 2';
    editor.dispatchEvent(new Event('input'));
    await flush();
    harness.getTask.mockResolvedValue({
      ...running,
      latestRecord: {
        ...running.latestRecord,
        status: 'STOPPED',
        log: '已确认停止',
      },
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(
      host.querySelector<HTMLTextAreaElement>('[data-editor]')?.value,
    ).toBe('SELECT 2');
    expect(button('停止')?.disabled).toBe(true);
    const calls = harness.getTask.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(harness.getTask).toHaveBeenCalledTimes(calls);
    expect(harness.updateTask).not.toHaveBeenCalled();
  });
  it('组件卸载后活动实例不再轮询', async () => {
    vi.useFakeTimers();
    harness.getTask.mockResolvedValue({
      ...currentTask(),
      latestRecord: { id: '9007199254740997', status: 'RUNNING' },
    });
    await mount(Workbench, {
      taskId: '9007199254740993',
      workloadMode: 'STREAMING',
    });
    app?.unmount();
    app = undefined;
    const calls = harness.getTask.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(harness.getTask).toHaveBeenCalledTimes(calls);
  });
  it('新增仅保存刷新目录且保留当前编辑任务，保存并配置才打开新任务', async () => {
    await mount(Workbench, {
      taskId: '9007199254740993',
      workloadMode: 'STREAMING',
    });
    const previousQueries = harness.getTask.mock.calls.length;
    harness.infoSaved?.('9007199254740995', false);
    await flush();
    expect(harness.getTask).toHaveBeenCalledTimes(previousQueries);
    harness.infoSaved?.('9007199254740995', true);
    await flush();
    expect(harness.getTask).toHaveBeenLastCalledWith('9007199254740995');
  });
  it.each(['BATCH', 'STREAMING'] as const)(
    '列表模式%s传给分页与统计，详情保留同一模式',
    async (workloadMode) => {
      await mount(DevelopmentTasks, { workloadMode });
      expect(harness.getPage).toHaveBeenCalledWith(
        expect.objectContaining({ pageNo: 1, pageSize: 10, workloadMode }),
      );
      expect(harness.getStatistics).toHaveBeenCalledWith(workloadMode);
      expect(
        harness.columns.map((column) => column.title).includes('策略与调度'),
      ).toBe(workloadMode === 'BATCH');
      expect(host.textContent?.includes('调度状态:')).toBe(
        workloadMode === 'BATCH',
      );
      button('详情')?.click();
      await flush();
      expect(harness.push).toHaveBeenLastCalledWith({
        name: 'DataforgeDevTaskDetail',
        params: { id: '9007199254740993' },
        query: { workloadMode },
      });
    },
  );
});
