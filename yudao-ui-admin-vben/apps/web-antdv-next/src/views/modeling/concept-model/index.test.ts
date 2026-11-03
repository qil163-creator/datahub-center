import type { App, SetupContext } from 'vue';

import type { ConceptModelApi } from '#/api/modeling/concept-model';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getConceptModelPage,
  updateConceptModelStatus,
} from '#/api/modeling/concept-model';

import Index from './index.vue';

type Action = {
  disabled?: boolean;
  ifShow?: boolean;
  label: string;
  onClick?: () => Promise<void>;
};
type Query = (
  params: { page: { currentPage: number; pageSize: number } },
  values: Record<string, unknown>,
) => Promise<{ list: ConceptModelApi.Model[]; total: number }>;
const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  query: undefined as Query | undefined,
  actions: [] as Action[],
  row: {} as ConceptModelApi.Model,
  reload: vi.fn(),
  clear: vi.fn(),
  close: vi.fn(),
  confirm: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '-' }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  confirm: (...args: unknown[]) => harness.confirm(...args),
  useVbenModal: () => {
    const api = {};
    return [
      () => {
        Object.assign(api, {
          close: harness.close,
          setData: vi.fn().mockReturnThis(),
          open: vi.fn(),
        });
        return null;
      },
      api,
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Tag: () => null,
  message: { success: vi.fn() },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: (props: { actions: Action[] }) => {
    if (props.actions.some((action) => action.label === '启用'))
      harness.actions = props.actions;
    return null;
  },
  useVbenVxeGrid: (options: {
    gridOptions: { proxyConfig: { ajax: { query: Query } } };
  }) => {
    harness.query = options.gridOptions.proxyConfig.ajax.query;
    return [
      (_: unknown, { slots }: SetupContext) =>
        h('div', [
          slots['toolbar-tools']?.(),
          slots.actions?.({ row: harness.row }),
        ]),
      {
        query: vi.fn(),
        reload: harness.reload,
        grid: { loadData: harness.clear },
      },
    ];
  },
}));
vi.mock('#/api/modeling/concept-model', () => ({
  getConceptModelPage: vi.fn(),
  updateConceptModelStatus: vi.fn(),
  deleteConceptModel: vi.fn(),
}));
vi.mock('./modules/form.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
const params = { page: { currentPage: 1, pageSize: 10 } };
async function flush() {
  await nextTick();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: [
      'modeling:concept-model:query',
      'modeling:concept-model:update',
    ],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({
    userInfo: { userId: '1' },
    userRoles: ['modeler'],
  });
  harness.row = {
    id: '9007199254740995',
    revision: '9007199254740993',
    status: 'DRAFT',
    name: '客户',
    code: 'customer',
    dataDomainId: '10',
  };
  harness.confirm.mockResolvedValue(undefined);
  host = document.createElement('div');
  app = createApp(Index);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('概念模型列表请求与操作隔离', () => {
  it('快速筛选时，旧响应不能覆盖较新的查询结果', async () => {
    let resolve!: (value: {
      list: ConceptModelApi.Model[];
      total: number;
    }) => void;
    vi.mocked(getConceptModelPage).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done;
      }),
    );
    vi.mocked(getConceptModelPage).mockResolvedValueOnce({
      list: [harness.row],
      total: 1,
    });
    const old = harness.query!(params, { keyword: 'old' });
    const stale = old.catch((error: unknown) => error);
    expect(await harness.query!(params, { keyword: 'new' })).toEqual({
      list: [harness.row],
      total: 1,
    });
    resolve({ list: [], total: 0 });
    expect(await stale).toMatchObject({ message: '概念模型查询已失效' });
  });
  it('角色变更立即清空列表并拒绝迟到响应，移除查询权限后不请求接口', async () => {
    let resolve!: (value: {
      list: ConceptModelApi.Model[];
      total: number;
    }) => void;
    vi.mocked(getConceptModelPage).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const old = harness.query!(params, {});
    const stale = old.catch((error: unknown) => error);
    harness.user.userRoles = ['viewer'];
    await flush();
    expect(harness.clear).toHaveBeenCalledWith([]);
    expect(harness.close).toHaveBeenCalled();
    resolve({ list: [harness.row], total: 1 });
    expect(await stale).toMatchObject({ message: '概念模型查询已失效' });
    harness.access.accessCodes = [];
    expect(await harness.query!(params, {})).toEqual({ list: [], total: 0 });
    expect(getConceptModelPage).toHaveBeenCalledTimes(1);
  });
  it('同一行重复启用只发送一次，失败后释放忙碌状态以便重试', async () => {
    let reject!: (reason: Error) => void;
    vi.mocked(updateConceptModelStatus).mockReturnValueOnce(
      new Promise((_, fail) => {
        reject = fail;
      }),
    );
    const activate = harness.actions.find(
      (action) => action.label === '启用',
    )!.onClick!;
    const first = activate();
    const failure = first.catch((error: unknown) => error);
    await activate();
    await flush();
    expect(updateConceptModelStatus).toHaveBeenCalledTimes(1);
    reject(new Error('暂时失败'));
    expect(await failure).toMatchObject({ message: '暂时失败' });
    vi.mocked(updateConceptModelStatus).mockResolvedValue(true);
    await activate();
    expect(updateConceptModelStatus).toHaveBeenCalledTimes(2);
    expect(updateConceptModelStatus).toHaveBeenLastCalledWith(
      '9007199254740995',
      '9007199254740993',
      'ACTIVE',
    );
  });
});
