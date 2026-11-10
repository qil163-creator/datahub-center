import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SubjectDomains from './index.vue';

type SubjectDomainTestInstance = {
  setupState: {
    chooseDomain: (id: string) => void;
    name: string;
    parentId?: string;
  };
};

type GridState = {
  data?: { id: string; parentId: string }[];
  pagerConfig?: {
    currentPage?: number;
    enabled?: boolean;
    pageSize?: number;
    total?: number;
  };
};
type GridOptions = {
  gridEvents: {
    pageChange: (event: { currentPage: number; pageSize: number }) => void;
  };
  gridOptions: GridState;
};

const harness = vi.hoisted(() => ({
  route: {} as { path: string; query: { detailId?: string | string[] } },
  navigate: vi.fn(),
  setSearchValues: vi.fn(),
  setSearchSubmission: vi.fn(),
  getSubjects: vi.fn(),
  setGridOptions: vi.fn(),
  gridOptions: undefined as GridOptions | undefined,
}));
vi.mock('vue-router', () => ({ useRoute: () => harness.route, useRouter: () => ({ push: harness.navigate }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: '', accessCodes: [] }),
  useUserStore: () => ({ userInfo: { userId: 1 }, userRoles: [] }),
}));
vi.mock('#/hooks/use-object-request', () => ({ useObjectRequest: () => ({ begin: () => () => true }) }));
vi.mock('../planning/use-planning-options', async () => {
  const { ref } = await import('vue');
  return { usePlanningOptions: () => ({ domains: ref([]), loading: ref(false), failed: ref(false), reload: vi.fn(), userName: () => '', userPhone: () => '' }) };
});
vi.mock('../planning/entity/form.vue', () => ({ default: () => null }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  confirm: vi.fn(),
  useVbenModal: () => [() => null, { setData: vi.fn() }],
}));
vi.mock('antdv-next', () => ({
  message: {},
  theme: { defaultAlgorithm: undefined },
  ConfigProvider: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
  Button: () => null,
  Input: () => null,
  Select: () => null,
  Switch: () => null,
  Tooltip: (_: unknown, { slots }: SetupContext) => h('span', slots.default?.()),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: () => null,
  useVbenVxeGrid: (options: GridOptions) => {
    harness.gridOptions = options;
    return [() => null, { query: vi.fn(), setGridOptions: harness.setGridOptions, formApi: { setValues: harness.setSearchValues, setLatestSubmissionValues: harness.setSearchSubmission } }];
  },
}));
vi.mock('#/api/modeling/subjectdomain', () => ({
  deleteSubjectDomain: vi.fn(),
  getSubjectDomainList: harness.getSubjects,
  updateSubjectDomainStatus: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('../dwlayer/modules/text-ellipsis-cell.vue', () => ({
  default: () => null,
}));
vi.mock('./modules/form.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  await nextTick();
  await nextTick();
}
async function mount(props: { dataDomainId?: string; embedded?: boolean } = {}) {
  host = document.createElement('div');
  app = createApp(SubjectDomains, props);
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.gridOptions = undefined;
  harness.getSubjects.mockResolvedValue([]);
  harness.route = reactive({ path: '/configured/subject-domain', query: {} });
});

describe('数据板块详情的主题域分页', () => {
  it('按根主题域分页并保留子主题域，切换页码和页大小后更新结果', async () => {
    const roots = Array.from({ length: 21 }, (_, index) => ({
      id: String(index + 1),
      parentId: '0',
      createTime: 21 - index,
    }));
    harness.getSubjects.mockResolvedValue([
      ...roots,
      { id: 'child-1', parentId: '1', createTime: 0 },
      { id: 'child-21', parentId: '21', createTime: 0 },
    ]);

    await mount({ dataDomainId: '34', embedded: true });
    expect(harness.gridOptions?.gridOptions.pagerConfig).toMatchObject({
      enabled: true,
      pageSize: 20,
    });
    const latest = () => harness.setGridOptions.mock.calls
      .map(([options]) => options as GridState)
      .filter((options) => options.data && options.pagerConfig)
      .at(-1)!;
    expect(latest().pagerConfig).toMatchObject({ total: 21, currentPage: 1, pageSize: 20 });
    expect(latest().data?.map((row) => row.id)).toContain('child-1');
    expect(latest().data?.map((row) => row.id)).not.toContain('child-21');
    expect(latest().data).toHaveLength(21);

    harness.gridOptions!.gridEvents.pageChange({ currentPage: 2, pageSize: 20 });
    expect(latest().pagerConfig).toMatchObject({ total: 21, currentPage: 2, pageSize: 20 });
    expect(latest().data?.map((row) => row.id)).toEqual(['21', 'child-21']);
    expect(latest().data?.find((row) => row.id === 'child-21')?.parentId).toBe('21');

    harness.gridOptions!.gridEvents.pageChange({ currentPage: 1, pageSize: 10 });
    expect(latest().pagerConfig).toMatchObject({ total: 21, currentPage: 1, pageSize: 10 });
    expect(latest().data).toHaveLength(11);
    expect(latest().data?.map((row) => row.id)).toContain('child-1');
    expect(latest().data?.map((row) => row.id)).not.toContain('11');
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('subject domain reference navigation', () => {
  it('主题域类目可以展开和收起', async () => {
    await mount();
    const root = host.querySelector<HTMLButtonElement>('.planning-tree-root');
    expect(root?.getAttribute('aria-expanded')).toBe('true');
    root?.click();
    await flush();
    expect(root?.getAttribute('aria-expanded')).toBe('false');
  });

  it('切换数据板块时清空搜索字段和原生表单内的旧条件', async () => {
    await mount();
    const instance = app!._instance as unknown as SubjectDomainTestInstance;
    const state = instance.setupState;
    state.name = '旧板块名称';
    state.parentId = '9007199254740993';
    state.chooseDomain('9007199254740995');
    expect(state.name).toBe('');
    expect(state.parentId).toBeUndefined();
    expect(harness.setSearchValues).toHaveBeenLastCalledWith({ name: '', parentId: undefined });
    expect(harness.setSearchSubmission).toHaveBeenLastCalledWith({ name: '', parentId: undefined });
  });

  it.each(['12', '9007199254740993123'])(
    'opens the referenced subject domain without changing identifier %s',
    async (id) => {
      harness.route.query.detailId = id;
      await mount();
      expect(harness.navigate).toHaveBeenCalledWith({ name: 'ModelingSubjectDomainDetail', params: { id }, query: {} });
      expect(harness.navigate).toHaveBeenCalledOnce();
    },
  );

  it('opens another reference in a retained list and ignores other pages', async () => {
    await mount();
    harness.route.query.detailId = '12';
    await flush();
    harness.route.path = '/another/page';
    harness.route.query.detailId = '24';
    await flush();
    expect(harness.navigate).toHaveBeenCalledOnce();
    harness.route.path = '/configured/subject-domain';
    await flush();
    expect(harness.navigate).toHaveBeenLastCalledWith({ name: 'ModelingSubjectDomainDetail', params: { id: '24' }, query: {} });
  });

  it('rejects malformed or repeated identifier parameters', async () => {
    harness.route.query.detailId = ['12', '24'];
    await mount();
    harness.route.query.detailId = '1.5';
    await flush();
    expect(harness.navigate).not.toHaveBeenCalled();
  });
});
