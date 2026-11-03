import type { App } from 'vue';

import { createApp, defineComponent, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import ModelList from './model-list.vue';

const state = vi.hoisted(() => ({
  access: { accessToken: 'first', refreshToken: '' },
  user: { userInfo: { userId: 'first' } },
  load: vi.fn(),
  page: vi.fn(),
  reset: vi.fn(),
  latest: vi.fn(),
  formValues: {} as Record<string, unknown>,
  submit: undefined as ((values: Record<string, unknown>) => void) | undefined,
  select: undefined as ((keys: string[]) => void) | undefined,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => state.user,
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/adapter/vxe-table', () => ({ TableAction: () => null }));
vi.mock('#/api/governance/masterdomain', () => ({ getMasterDomainSimpleList: async () => [] }));
vi.mock('#/api/governance/masterdata', () => ({
  getMaintainModelDataCount: vi.fn(),
  getMaintainModelPage: state.page,
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Button: () => null,
  Input: () => null,
  Tooltip: () => null,
  Tree: defineComponent({
    emits: ['select'],
    setup(_props, { emit }) {
      state.select = (keys) => emit('select', keys);
      return () => null;
    },
  }),
}));
vi.mock('../../metadata-common/use-metadata-grid', () => ({
  useMetadataGrid: (
    _columns: unknown,
    fetchPage: (page: { pageNo: number; pageSize: number }) => Promise<unknown>,
    options: { handleSubmit: (values: Record<string, unknown>) => void },
  ) => {
    state.submit = options.handleSubmit;
    state.load.mockImplementation(() => fetchPage({ pageNo: 1, pageSize: 10 }));
    return {
      Grid: () => null,
      load: state.load,
      error: ref(false),
      gridApi: { formApi: { reset: state.reset, setLatestSubmissionValues: state.latest } },
    };
  },
}));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'first', refreshToken: '' });
  state.user = reactive({ userInfo: { userId: 'first' } });
  state.page.mockResolvedValue({ list: [], total: 0 });
  state.reset.mockImplementation(async () => { state.formValues = {}; });
  host = document.createElement('div');
  app = createApp(ModelList);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

it('用户变化清理模型搜索、类目与原生表单，后续请求不沿用前一用户条件', async () => {
  state.formValues = { name: '旧模型', tableName: 'OLD_TABLE', status: 1, createTime: ['2026-10-01', '2026-10-02'] };
  state.submit?.(state.formValues);
  state.select?.(['9007199254740993']);
  await flush();
  expect(state.page).toHaveBeenLastCalledWith(expect.objectContaining({ name: '旧模型', tableName: 'OLD_TABLE', domainId: '9007199254740993' }));
  state.user.userInfo.userId = 'second';
  await flush();
  expect(state.formValues).toEqual({});
  expect(state.reset).toHaveBeenCalledOnce();
  expect(state.latest).toHaveBeenCalledExactlyOnceWith({});
  expect(state.page).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 10, name: '', tableName: '', status: 1, createTime: undefined, domainId: undefined });
});
