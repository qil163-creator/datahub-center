import type { App, SetupContext } from 'vue';

import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import EntityTable from './table.vue';

type Entity = BusinessEntityApi.Entity;
interface GridOptions {
  gridOptions: {
    checkboxConfig: { checkStrictly: boolean; showHeader: boolean };
  };
  gridEvents: { toggleTreeExpand: () => void };
  formOptions: {
    handleSubmit: (values: {
      entitySubtypes?: BusinessEntityApi.EntitySubtype[];
      keyword?: string;
      status?: BusinessEntityApi.Status;
    }) => Promise<void>;
  };
}
interface GridState {
  data: Entity[];
  loading: boolean;
  pagerConfig?: { total?: number };
}
const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['modeling:business-entity:query'],
  },
  options: undefined as GridOptions | undefined,
  state: { data: [], loading: false } as GridState,
  get: vi.fn(),
  setGridOptions: vi.fn(),
  expand: vi.fn(),
  expandedRecords: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/common-ui', () => ({
  confirm: vi.fn(),
  useVbenModal: () => [() => null, {}],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '' }));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { attrs, slots }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Switch: () => null,
  Tag: (_: unknown, { slots }: SetupContext) =>
    h('span', { 'data-testid': 'relation-tag' }, slots.default?.()),
  message: { info: vi.fn(), success: vi.fn() },
}));
vi.mock('#/api/request', () => ({
  requestClient: {
    get: harness.get,
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: () => null,
  useVbenVxeGrid: (options: GridOptions) => {
    harness.options = options;
    return [
      (_: unknown, { slots }: SetupContext) => {
        const rows = (items: Entity[]): ReturnType<typeof h>[] =>
          items.flatMap((row) => [
            h('div', { 'data-row-id': String(row.id) }, slots.info?.({ row })),
            ...rows(row.children ?? []),
          ]);
        return h('div', [
          slots['toolbar-tools']?.(),
          h('div', { 'data-testid': 'rows' }, rows(harness.state.data)),
        ]);
      },
      {
        setGridOptions: harness.setGridOptions,
        formApi: { reset: vi.fn() },
        grid: {
          clearCheckboxRow: vi.fn(),
          setAllTreeExpand: harness.expand,
          getTreeExpandRecords: harness.expandedRecords,
        },
      },
    ];
  },
}));
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: () => null,
}));
vi.mock('./detail.vue', () => ({ default: () => null }));
vi.mock('./form.vue', () => ({ default: () => null }));

function entity(id: BusinessEntityApi.Id, name: string): Entity {
  return {
    id,
    name,
    code: name,
    dataDomainId: '29',
    subjectDomainId: '55',
    entityType: 'ACTIVITY',
    entitySubtype: 'EVENT',
    status: 0,
    revision: '9007199254740999',
    description: '',
    associatedEntityIds: [],
    predecessorEntityIds: [],
    processEventIds: [],
  };
}
function process(): Entity {
  return {
    ...entity('9007199254740995', '洪水预警处置流程'),
    entitySubtype: 'PROCESS',
    children: [
      entity(71, '洪水预警触发'),
      entity('9007199254741007', '防汛响应启动'),
      entity(74, '防汛响应结束'),
    ],
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let count = 0; count < 5; count += 1) await nextTick();
}
async function mount() {
  host = document.createElement('div');
  app = createApp(EntityTable, {
    dataDomainId: '9007199254740993',
    subjectDomainId: '55',
  });
  app.mount(host);
  await flush();
}
function button(label: string): HTMLButtonElement {
  const match = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (element) => element.textContent?.trim() === label,
  );
  expect(match).toBeDefined();
  return match!;
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['modeling:business-entity:query'],
  });
  harness.state = reactive({ data: [], loading: false });
  harness.get.mockResolvedValue({ list: [process()], total: 1 });
  harness.expand.mockResolvedValue(undefined);
  harness.expandedRecords.mockReturnValue([]);
  harness.setGridOptions.mockImplementation((options: Partial<GridState>) => {
    Object.assign(harness.state, options);
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('业务实体包含关系树表', () => {
  it('请求树分页接口，并按接口顺序展示流程和无损编号的子事件', async () => {
    await mount();

    expect(harness.options!.gridOptions.checkboxConfig).toMatchObject({
      checkStrictly: true,
      showHeader: true,
    });

    expect(harness.get).toHaveBeenCalledExactlyOnceWith(
      '/modeling/business-entity/tree-page',
      {
        params: expect.objectContaining({
          dataDomainId: '9007199254740993',
          subjectDomainId: '55',
          pageNo: 1,
          pageSize: 10,
        }),
      },
    );
    expect(
      [...host.querySelectorAll<HTMLElement>('[data-row-id]')].map(
        (row) => row.dataset.rowId,
      ),
    ).toEqual(['9007199254740995', '71', '9007199254741007', '74']);
    expect(host.querySelectorAll('[data-testid="relation-tag"]')).toHaveLength(
      1,
    );
    expect(
      host.querySelector('[data-testid="relation-tag"]')?.textContent,
    ).toBe('包含关系');
    expect(harness.state.loading).toBe(false);
    expect(harness.expand).toHaveBeenLastCalledWith(true);
  });

  it('收起和展开按钮调用原生树表方法，不重新请求实体列表', async () => {
    await mount();
    harness.expand.mockClear();

    button('收起').click();
    await flush();
    expect(harness.expand).toHaveBeenCalledExactlyOnceWith(false);
    expect(button('展开')).toBeDefined();
    expect(harness.get).toHaveBeenCalledTimes(1);

    button('展开').click();
    await flush();
    expect(harness.expand).toHaveBeenLastCalledWith(true);
    expect(button('收起')).toBeDefined();
    expect(harness.get).toHaveBeenCalledTimes(1);
  });

  it('手动折叠最后一个流程后同步工具栏，点击展开无需重新加载', async () => {
    await mount();
    harness.expand.mockClear();
    harness.expandedRecords.mockReturnValue([]);

    harness.options!.gridEvents.toggleTreeExpand();
    await flush();
    button('展开').click();
    await flush();

    expect(harness.expand).toHaveBeenCalledExactlyOnceWith(true);
    expect(harness.get).toHaveBeenCalledTimes(1);
    harness.expandedRecords.mockReturnValue([process()]);
    harness.options!.gridEvents.toggleTreeExpand();
    await flush();
    expect(button('收起')).toBeDefined();
  });

  it('搜索仅匹配事件时保留独立行，并传递实体类型和状态筛选', async () => {
    await mount();
    harness.get.mockResolvedValueOnce({
      list: [entity('9007199254741007', '防汛响应启动')],
      total: 1,
    });
    await harness.options!.formOptions.handleSubmit({
      keyword: '  防汛响应启动  ',
      entitySubtypes: ['EVENT'],
      status: 0,
    });
    await flush();

    expect(harness.get).toHaveBeenLastCalledWith(
      '/modeling/business-entity/tree-page',
      {
        params: expect.objectContaining({
          keyword: '防汛响应启动',
          entitySubtypes: ['EVENT'],
          status: 0,
        }),
      },
    );
    expect(host.querySelectorAll('[data-row-id]')).toHaveLength(1);
    expect(host.textContent).toContain('防汛响应启动');
    expect(host.textContent).not.toContain('洪水预警处置流程');
    expect(host.querySelector('[data-testid="relation-tag"]')).toBeNull();
  });

  it.each(['先', '后'])(
    '旧请求%s返回时不覆盖新查询数据或提前解除加载状态',
    async (order) => {
      const first = deferred<{ list: Entity[]; total: number }>();
      const latest = deferred<{ list: Entity[]; total: number }>();
      harness.get
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(latest.promise);
      await mount();
      const search = harness.options!.formOptions.handleSubmit({
        keyword: '新查询',
      });
      await flush();
      expect(harness.state.loading).toBe(true);

      if (order === '先') {
        first.resolve({ list: [process()], total: 1 });
        await flush();
      }
      expect(harness.state.data).toEqual([]);
      expect(harness.state.loading).toBe(true);
      latest.resolve({ list: [entity(99, '新查询')], total: 1 });
      await search;
      await flush();
      if (order === '后') {
        first.resolve({ list: [process()], total: 1 });
        await flush();
      }

      expect(harness.state.data.map((row) => row.id)).toEqual(['99']);
      expect(harness.state.loading).toBe(false);
      expect(host.textContent).toContain('新查询');
      expect(host.textContent).not.toContain('洪水预警处置流程');
      expect(harness.expand).toHaveBeenCalledTimes(1);
    },
  );
});
