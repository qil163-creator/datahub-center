/* eslint-disable vue/one-component-per-file -- 隔离页面替身用于验证路由缓存切换后的筛选一致性。 */
import type { App } from 'vue';

import type { OpsInstanceApi } from '#/api/dataforge/ops';

import { createApp, defineComponent, h, KeepAlive, nextTick, ref } from 'vue';

import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/ops';

import Workbench from './workbench.vue';

vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/utils', () => ({ downloadFileFromBlobPart: vi.fn(), formatDate: () => '2026-10-22' }));
const gridHarness = vi.hoisted(() => ({ options: undefined as any, setOptions: vi.fn(), setLoading: vi.fn() }));
vi.mock('#/adapter/business-vxe-table', () => ({
  NativeVxeButton: defineComponent({ setup: (_, { slots }) => () => h('button', slots.default?.()) }),
  useVbenVxeGrid: (options: any) => {
    gridHarness.options = options.gridOptions;
    return [defineComponent({
      props: { tableData: { type: Array as () => any[], default: () => [] }, tableTitle: { type: String, default: '' } },
      setup: (props, { slots }) => () => h('div', { class: 'qo-table-toolbar' }, [
        h('h2', { class: 'native-list-title' }, props.tableTitle),
        slots['toolbar-tools']?.(),
        h('button', { 'aria-label': '刷新列表', onClick: gridHarness.options.toolbarConfig.refreshOptions.queryMethod }),
        h('button', { 'aria-label': '列设置' }),
        ...['idHeader', 'timeHeader', 'startHeader', 'endHeader', 'createdHeader'].map((name) => slots[name]?.()),
        h('table', [h('tbody', props.tableData.map((row) => h('tr', { class: row.depth ? 'qo-child-row' : '' },
          ['id', 'instanceName', 'taskName', 'executionType', 'status', 'time', 'startTime', 'endTime', 'duration', 'runCount', 'owner', 'creator', 'created', 'actions'].map((name) => h('td', slots[name]?.({ row }))))))]),
        slots.pager?.(),
      ]),
    }), { grid: {}, setGridOptions: gridHarness.setOptions, setLoading: gridHarness.setLoading }];
  },
}));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/components/tree-toolbar/index.vue', () => ({
  default: defineComponent({
    props: { expanded: Boolean, loading: Boolean, refreshLabel: { type: String, default: '刷新树' } },
    emits: ['toggleExpand', 'refresh'],
    setup: (props, { emit }) => () => h('div', [
      h('button', { 'aria-label': props.expanded ? '全部收起' : '全部展开', onClick: () => emit('toggleExpand') }),
      h('button', { 'aria-label': props.refreshLabel, disabled: props.loading, onClick: () => emit('refresh') }),
    ]),
  }),
}));
vi.mock('#/api/dataforge/ops', () => ({
  getOpsInstancePage: vi.fn(async () => ({ list: [], total: 0 })),
  getOpsInstanceStatistics: vi.fn(async () => ({ running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0, statisticTime: '2026-10-22 19:11:00', importedSnapshot: true })),
  getOpsInstanceCategories: vi.fn(async () => [{ id: 'source:2', parentId: '0', name: '测试类目' }]),
  getOpsInstance: vi.fn(), getOpsInstanceLog: vi.fn(), downloadOpsInstanceLog: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  return { Alert: Container, Button: Container, ConfigProvider: Container, DateRangePicker: Container, Dropdown: Container, Pagination: Container, Select: Container, Spin: Container, Tooltip: Container, Modal: Container, Switch: Container, TextArea: Container,
    theme: { defaultAlgorithm: {} },
    Input: defineComponent({ props: { value: { type: String, default: '' } }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }) }),
    Tree: defineComponent({ props: { expandedKeys: { type: Array, default: () => [] }, treeData: { type: Array, default: () => [] } }, emits: ['select'], setup: (props, { emit }) => () => h('button', { class: 'select-category', 'data-expanded': JSON.stringify(props.expandedKeys), 'data-tree': JSON.stringify(props.treeData), onClick: () => emit('select', ['source:2']) }, '选择类目') }),
  };
});

let app: App;
let host: HTMLDivElement;
const active = ref(true);
const kind = ref<OpsInstanceApi.Kind>('INTEGRATION');
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick(); }
beforeEach(() => {
  vi.clearAllMocks(); active.value = true; kind.value = 'INTEGRATION';
  vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [], total: 0 });
  vi.mocked(api.getOpsInstanceCategories).mockResolvedValue([{ id: 'source:2', parentId: '0', name: '测试类目' }]);
  host = document.createElement('div'); document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(KeepAlive, () => active.value ? h(Workbench, { kind: kind.value }) : null) }));
  app.use(createPinia()); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });

describe('实例页面缓存筛选一致性', () => {
  it.each(['INTEGRATION', 'DEVELOPMENT', 'JOB'] as const)('%s 类目工具可展开、收起、刷新并保留搜索前展开状态', async (instanceKind) => {
    kind.value = instanceKind;
    await flush();
    const categories = [
      { id: 'source:2', parentId: '0', name: '父类目' },
      { id: 'source:3', parentId: 'source:2', name: '子类目' },
      { id: 'source:4', parentId: 'source:3', name: '目标叶子' },
    ];
    host.querySelector<HTMLElement>('[aria-label="全部收起"]')?.click();
    await flush();
    vi.mocked(api.getOpsInstanceCategories).mockResolvedValue(categories);
    const calls = vi.mocked(api.getOpsInstanceCategories).mock.calls.length;
    host.querySelector<HTMLButtonElement>('[aria-label="刷新类目"]')?.click();
    await flush();
    expect(api.getOpsInstanceCategories).toHaveBeenCalledTimes(calls + 1);
    expect(host.querySelector('.select-category')?.getAttribute('data-tree')).toContain('目标叶子');
    host.querySelector<HTMLElement>('[aria-label="全部展开"]')?.click();
    await flush();
    expect(JSON.parse(host.querySelector('.select-category')!.getAttribute('data-expanded')!)).toEqual(['__root', 'source:2', 'source:3']);
    host.querySelector<HTMLElement>('[aria-label="全部收起"]')?.click();
    await flush();
    const input = host.querySelector<HTMLInputElement>('[aria-label="搜索实例类目"]')!;
    input.value = '目标叶子'; input.dispatchEvent(new Event('input'));
    await flush();
    expect(JSON.parse(host.querySelector('.select-category')!.getAttribute('data-expanded')!)).toEqual(['__root', 'source:2', 'source:3']);
    input.value = ''; input.dispatchEvent(new Event('input'));
    await flush();
    expect(JSON.parse(host.querySelector('.select-category')!.getAttribute('data-expanded')!)).toEqual([]);
  });
  it('类目刷新中切换实例类型时忽略旧响应', async () => {
    await flush();
    let finish: (value: OpsInstanceApi.Category[]) => void = () => {};
    vi.mocked(api.getOpsInstanceCategories).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    host.querySelector<HTMLButtonElement>('[aria-label="刷新类目"]')?.click();
    await flush();
    expect(host.querySelector<HTMLButtonElement>('[aria-label="刷新类目"]')?.disabled).toBe(true);
    vi.mocked(api.getOpsInstanceCategories).mockResolvedValue([{ id: 'new', parentId: '0', name: '新类目' }]);
    kind.value = 'DEVELOPMENT';
    await flush();
    finish([{ id: 'old', parentId: '0', name: '过期类目' }]);
    await flush();
    expect(host.querySelector('.select-category')?.getAttribute('data-tree')).toContain('新类目');
    expect(host.querySelector('.select-category')?.getAttribute('data-tree')).not.toContain('过期类目');
  });
  it('显示各类实例列表标题和保留的工具按钮', async () => {
    await vi.waitFor(() => expect(host.querySelector('[aria-label="刷新列表"]')).not.toBeNull());
    expect(host.querySelector('.native-list-title')?.textContent).toBe('数据集成实例列表');
    expect(host.querySelector('[aria-label="全屏表格"]')).toBeNull();
    expect(host.querySelector('[aria-label="显示或隐藏搜索"]')).not.toBeNull();
    expect(host.querySelector('[aria-label="列设置"]')).not.toBeNull();
    expect(gridHarness.options.toolbarConfig).toMatchObject({ refresh: true, custom: true, zoom: false });
    expect(gridHarness.options.rowConfig.keyField).toBe('key');
    for (const [nextKind, title] of [
      ['DEVELOPMENT', '数据开发实例列表'],
      ['FULL_SYNC', '整库同步实例列表'],
      ['JOB', '作业管理实例列表'],
    ] as const) {
      kind.value = nextKind;
      await flush();
      expect(host.querySelector('.native-list-title')?.textContent).toBe(title);
    }
  });
  it('原生工具栏刷新保留当前类目和服务端排序', async () => {
    await flush();
    host.querySelector<HTMLButtonElement>('.select-category')?.click();
    await flush();
    host.querySelector<HTMLButtonElement>('[aria-label="按编号排序"]')?.click();
    await flush();
    host.querySelector<HTMLButtonElement>('[aria-label="刷新列表"]')?.click();
    await flush();
    expect(api.getOpsInstancePage).toHaveBeenLastCalledWith(expect.objectContaining({
      categoryId: 'source:2', sortField: 'id', sortOrder: 'desc',
    }));
  });
  it('真实周期类型SCHEDULE在列表显示为定时调度', async () => {
    await flush();
    const scheduled: OpsInstanceApi.Instance = {
      id: '9007199254740993', kind: 'JOB', origin: 'PLATFORM',
      taskId: '9007199254740995', taskName: '周期作业', instanceName: '周期作业实例',
      executionType: 'SCHEDULE', status: 'SUCCESS', queueState: 'DONE',
      scheduledTime: '2026-11-02 08:42:00',
    };
    vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [scheduled], total: 1 });
    kind.value = 'JOB';
    await flush();
    expect(host.querySelector('.qo-execution-type')?.textContent).toBe('定时调度');
  });
  it.each([['DEVELOPMENT', 'PLATFORM'], ['INTEGRATION', 'MANAGED']] as const)('%s作业子实例按%s来源查询真实日志', async (childKind, childOrigin) => {
    await flush();
    const child: OpsInstanceApi.Instance = {
      id: '9007199254740993', kind: childKind, origin: childOrigin,
      taskId: '9007199254740995', taskName: '每日水位统计', instanceName: '水位统计实例',
      executionType: 'START', status: 'SUCCESS',
    };
    vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [{
      ...child, id: '9007199254740997', kind: 'JOB', origin: 'PLATFORM', taskName: '河道作业',
      instanceName: '河道作业实例', children: [child],
    }], total: 1 });
    vi.mocked(api.getOpsInstance).mockResolvedValue(child);
    vi.mocked(api.getOpsInstanceLog).mockResolvedValue({ message: '任务完成', available: true, importedSnapshot: false });
    kind.value = 'JOB';
    await flush();
    expect(host.querySelector('.qo-child-row')).toBeNull();
    host.querySelector<HTMLButtonElement>('.qo-row-toggle')?.click();
    await flush();
    expect(host.querySelector('.qo-child-row')?.textContent).toContain('水位统计实例');
    host.querySelector<HTMLButtonElement>('.qo-child-row .qo-actions > div')?.click();
    await flush();
    expect(api.getOpsInstanceLog).toHaveBeenCalledWith({ id: child.id, kind: child.kind, origin: child.origin });
  });
  it('类目选择同时影响列表及统计，切页返回不保留不可见类目筛选', async () => {
    await flush();
    host.querySelector<HTMLButtonElement>('.select-category')?.click(); await flush();
    expect(api.getOpsInstancePage).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 'source:2' }));
    expect(api.getOpsInstanceStatistics).toHaveBeenLastCalledWith({ kind: 'INTEGRATION', categoryId: 'source:2', taskType: undefined });
    active.value = false; await flush(); active.value = true; await flush();
    expect(api.getOpsInstancePage).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: undefined }));
  });
});
