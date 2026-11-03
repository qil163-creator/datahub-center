import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TableModel from './index.vue';

interface GridOptions {
  formOptions: { schema: { defaultValue?: unknown; fieldName: string }[] };
  gridOptions: {
    proxyConfig: {
      ajax: {
        query: (
          page: { page: { currentPage: number; pageSize: number } },
          values: Record<string, unknown>,
        ) => Promise<unknown>;
      };
    };
  };
}
const harness = vi.hoisted(() => ({
  access: { accessToken: 'session', refreshToken: '' },
  route: {} as { path: string; query: { layerId?: string | string[] } },
  options: undefined as GridOptions | undefined,
  values: {} as Record<string, unknown>,
  load: vi.fn(),
  query: vi.fn(),
  setValues: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => harness.route,
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '' }));
vi.mock('antdv-next', () => ({
  Button: () => null,
  Dropdown: () => null,
  Menu: { Item: () => null },
  message: {},
  Modal: {},
  Switch: () => null,
  Tag: () => null,
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: () => null,
  useVbenVxeGrid: (options: GridOptions) => {
    harness.options = options;
    harness.values = Object.fromEntries(
      options.formOptions.schema.map((field) => [
        field.fieldName,
        field.defaultValue,
      ]),
    );
    return [
      () => null,
      {
        formApi: { setValues: harness.setValues },
        query: harness.query,
        grid: { loadData: vi.fn() },
      },
    ];
  },
}));
vi.mock('#/api/modeling/dwlayer', () => ({
  getDwLayerList: () => Promise.resolve([]),
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModelPage: (...args: unknown[]) => harness.load(...args),
  deleteTableModel: vi.fn(),
  updateTableModelEnabled: vi.fn(),
}));
vi.mock('../modelcommon/data', () => ({
  formatBelongingHierarchy: vi.fn(),
  getUserMobile: vi.fn(),
  getUserNickname: vi.fn(),
  optionLabel: vi.fn(),
  TABLE_TYPE_OPTIONS: [],
}));
vi.mock('../modelcommon/model-list-layout.vue', () => ({
  default: (_: unknown, { slots }: SetupContext) =>
    h('section', slots.default?.()),
}));
vi.mock('./modules/form.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 5; index += 1) await nextTick();
}
async function mount() {
  host = document.createElement('div');
  app = createApp(TableModel);
  app.directive('access', () => {});
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.route = reactive({
    path: '/modeling/table-model',
    query: { layerId: '9223372036854775806' },
  });
  harness.load.mockResolvedValue({ list: [], total: 0 });
  harness.setValues.mockImplementation(
    async (values: Record<string, unknown>) => {
      Object.assign(harness.values, values);
    },
  );
  harness.query.mockImplementation(() =>
    harness.options!.gridOptions.proxyConfig.ajax.query(
      { page: { currentPage: 1, pageSize: 20 } },
      harness.values,
    ),
  );
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('数仓分层到模型列表的筛选联动', () => {
  it('初次打开即携带无损 ID，同一标签切换分层同步表单和请求', async () => {
    await mount();
    await harness.query();
    expect(harness.load).toHaveBeenLastCalledWith(
      expect.objectContaining({ layerId: '9223372036854775806' }),
      expect.any(AbortSignal),
    );
    harness.route.query.layerId = '23';
    await flush();
    expect(harness.setValues).toHaveBeenLastCalledWith({
      layerId: '23',
      name: undefined,
      code: undefined,
    });
    expect(harness.load).toHaveBeenLastCalledWith(
      expect.objectContaining({ layerId: '23' }),
      expect.any(AbortSignal),
    );
    delete harness.route.query.layerId;
    await flush();
    expect(harness.load).toHaveBeenLastCalledWith(
      expect.objectContaining({ layerId: undefined }),
      expect.any(AbortSignal),
    );
  });

  it('拒绝重复参数和非法 ID，离开页面时不会应用其他页面的分层参数', async () => {
    harness.route.query.layerId = ['12', '13'];
    await mount();
    expect(harness.values.layerId).toBeUndefined();
    harness.route.query.layerId = '12e3';
    await flush();
    expect(harness.setValues).toHaveBeenLastCalledWith({
      layerId: undefined,
      name: undefined,
      code: undefined,
    });
    harness.setValues.mockClear();
    harness.route.path = '/other';
    harness.route.query.layerId = '99';
    await flush();
    expect(harness.setValues).not.toHaveBeenCalled();
  });
});
