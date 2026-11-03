/* eslint-disable vue/one-component-per-file -- Isolated UI controls exercise the real page lifecycle and permissions. */
import type { App, PropType } from 'vue';

import type { PageResult } from '@vben/request';

import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import {
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  reactive,
  ref,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  cancelMasterDistribute,
  getMasterDistributePage,
  handRunMasterDistribute,
  releaseMasterDistribute,
  updateMasterDistributeStatus,
} from '#/api/governance/masterdistribute';
import { getMasterPushCategoryTree } from '#/api/governance/masterdistribute/category';

import MasterDistributePage from './index.vue';

const state = vi.hoisted(() => ({
  canRelease: true,
  access: {} as { accessToken: number; refreshToken?: number },
  ajaxQuery: undefined as
    | (() => Promise<PageResult<GovernanceMasterDistributeApi.Distribute>>)
    | undefined,
  query: vi.fn(),
  reload: vi.fn(),
  submit: undefined as (() => Promise<void>) | undefined,
  reset: undefined as (() => Promise<void>) | undefined,
  pageChange: undefined as (() => void) | undefined,
  page: { currentPage: 4, pageSize: 20 },
  formValues: { status: 1 } as { name?: string; status?: number },
  submitted: { status: 1 } as { name?: string; status?: number },
  nativeBusy: false,
  applied: undefined as
    | PageResult<GovernanceMasterDistributeApi.Distribute>
    | undefined,
  success: vi.fn(),
  setGridOptions: vi.fn(),
  row: {
    id: '9007199254740993',
    name: '待核查派发',
    status: 0,
    blockingRunStatus: 5,
  } as GovernanceMasterDistributeApi.Distribute,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => state.canRelease }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => [
    defineComponent(() => () => null),
    { setData: vi.fn(), open: vi.fn(), close: vi.fn() },
  ],
  useVbenDrawer: () => [
    defineComponent(() => () => null),
    { setData: vi.fn(), open: vi.fn(), close: vi.fn() },
  ],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => ({ userInfo: { userId: 99 } }),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent(() => () => null),
  Tree: defineComponent({
    props: { treeData: { type: Array as PropType<Array<{ children?: Array<{ key: string; title: string }>; key: string; title: string }>>, default: () => [] } },
    emits: ['select'],
    setup: (props, { emit }) => () => h('div', props.treeData.flatMap((node) => [node, ...(node.children ?? [])]).map((node) => h('button', { onClick: () => emit('select', [node.key]) }, node.title))),
  }),
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Input: defineComponent(() => () => h('input')),
  Select: defineComponent(() => () => h('select')),
  Switch: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['change'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'button',
          {
            disabled: props.disabled,
            onClick: () => emit('change', !props.checked),
          },
          '切换启用',
        ),
  }),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  Tooltip: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  message: { loading: vi.fn(() => vi.fn()), success: state.success },
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/api/governance/masterdistribute/category', () => ({ getMasterPushCategoryTree: vi.fn() }));
vi.mock('#/api/governance/masterdistribute', () => ({
  cancelMasterDistribute: vi.fn(),
  deleteMasterDistribute: vi.fn(),
  getMasterDistributePage: vi.fn(),
  handRunMasterDistribute: vi.fn(),
  releaseMasterDistribute: vi.fn(),
  updateMasterDistributeStatus: vi.fn(),
  retryMasterDistribute: vi.fn(),
}));
vi.mock('./modules/detail.vue', () => ({ default: {} }));
vi.mock('./modules/form.vue', () => ({ default: {} }));
vi.mock('./modules/log.vue', () => ({ default: {} }));
vi.mock('#/adapter/vxe-table', () => {
  interface Action {
    label: string;
    disabled?: boolean;
    ifShow?: boolean;
    tooltip?: string;
    onClick?: () => void;
    popConfirm?: { confirm: () => void };
  }
  return {
    ACTION_ICON: {},
    TableAction: defineComponent({
      props: {
        actions: { type: Array as PropType<Action[]>, default: () => [] },
        dropDownActions: { type: Array as PropType<Action[]>, default: () => [] },
      },
      setup: (props) => () =>
        h('div', [
          ...(['actions', 'dropDownActions'] as const).map((group) =>
            h(
              'div',
              { 'data-action-group': group },
              (props[group] ?? [])
                .filter((action) => action.ifShow !== false)
                .map((action) =>
                  h(
                    'button',
                    {
                      disabled: action.disabled,
                      title: action.tooltip,
                      onClick: () =>
                        action.popConfirm
                          ? action.popConfirm.confirm()
                          : action.onClick?.(),
                    },
                    action.label,
                  ),
                ),
            ),
          ),
        ]),
    }),
    useVbenVxeGrid: (options: {
      formOptions: {
        handleReset: () => Promise<void>;
        handleSubmit: () => Promise<void>;
      };
      gridEvents: { pageChange: () => void };
      gridOptions: {
        proxyConfig: {
          ajax: {
            query: (
              pagination: { page: { currentPage: number; pageSize: number } },
              filters: { name?: string; status?: number },
            ) => Promise<PageResult<GovernanceMasterDistributeApi.Distribute>>;
            queryError: () => void;
            querySuccess: () => void;
          };
        };
      };
    }) => {
      state.submit = options.formOptions.handleSubmit;
      state.reset = options.formOptions.handleReset;
      state.pageChange = options.gridEvents.pageChange;
      state.ajaxQuery = () =>
        options.gridOptions.proxyConfig.ajax
          .query({ page: { ...state.page } }, { ...state.submitted })
          .then(
            (result) => {
              state.nativeBusy = false;
              state.applied = result;
              options.gridOptions.proxyConfig.ajax.querySuccess();
              return result;
            },
            (error: unknown) => {
              state.nativeBusy = false;
              options.gridOptions.proxyConfig.ajax.queryError();
              throw error;
            },
          );
      state.query.mockImplementation(async () => {
        // VXE4.21.2 的 commitProxy 在已有请求时直接返回，不再调用 ajax。
        if (state.nativeBusy) return;
        state.nativeBusy = true;
        return await state.ajaxQuery!().catch(() => undefined);
      });
      state.reload.mockImplementation(() => {
        state.page.currentPage = 1;
        return state.query();
      });
      return [
        defineComponent({
          setup:
            (_, { slots }) =>
            () =>
              h('section', [
                slots.status?.({ row: state.row }),
                slots.blockingRunStatus?.({ row: state.row }),
                slots.actions?.({ row: state.row }),
              ]),
        }),
        {
          query: state.query,
          reload: state.reload,
          setGridOptions: state.setGridOptions,
          formApi: {
            getValues: async () => ({ ...state.formValues }),
            setLatestSubmissionValues: (values: typeof state.submitted) => {
              state.submitted = values;
            },
            reset: async () => {
              state.formValues = {};
            },
          },
        },
      ];
    },
  };
});
let app: App | undefined;
let element: HTMLDivElement | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  state.canRelease = true;
  state.row.distributeType = 1;
  state.access = reactive({ accessToken: 1, refreshToken: undefined });
  state.nativeBusy = false;
  state.page = { currentPage: 4, pageSize: 20 };
  state.formValues = { status: 1 };
  state.submitted = { status: 1 };
  state.applied = undefined;
  vi.mocked(getMasterDistributePage).mockResolvedValue({ list: [], total: 0 });
  vi.mocked(getMasterPushCategoryTree).mockResolvedValue([{ id: '9007199254740997', parentId: 0, name: '主数据目录', sort: 0, status: 0 }]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  element?.remove();
  vi.useRealTimers();
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, reject, resolve };
}
async function flush() {
  for (let index = 0; index < 12; ++index) await Promise.resolve();
  await nextTick();
}

describe('派发列表持续同步异步状态', () => {
  it('初始查询失败仍由queryError释放门禁，排队的新搜索不会丢失', async () => {
    await mountRow(0);
    const pending =
      deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
    const fresh = {
      list: [{ id: 'retry-search', blockingRunStatus: 0 }],
      total: 1,
    };
    vi.mocked(getMasterDistributePage)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue(fresh);
    const first = state.query();
    state.formValues = { name: '新筛选' };
    const searched = state.submit!();
    await flush();
    expect(getMasterDistributePage).toHaveBeenCalledOnce();
    pending.reject(new Error('query failed'));
    await first;
    await searched;
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 20,
      name: '新筛选',
    });
    expect(state.applied).toEqual(fresh);
  });
  it.each(['搜索', '重置'])(
    '轮询在途时%s仍排队生效，提交筛选并返回第一页',
    async (action) => {
      vi.useFakeTimers();
      await mountRow(2, 1);
      const pending =
        deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
      const fresh = {
        list: [{ id: 'filtered', blockingRunStatus: 0 }],
        total: 1,
      };
      vi.mocked(getMasterDistributePage)
        .mockResolvedValueOnce({ list: [{ ...state.row }], total: 1 })
        .mockReturnValueOnce(pending.promise)
        .mockResolvedValue(fresh);
      await state.query();
      await vi.advanceTimersByTimeAsync(5000);
      state.formValues = { name: '新筛选' };
      const submitted = action === '搜索' ? state.submit!() : state.reset!();
      await flush();
      expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
      pending.resolve({
        list: [{ id: 'old-page', blockingRunStatus: 0 }],
        total: 1,
      });
      await submitted;
      await flush();
      expect(getMasterDistributePage).toHaveBeenCalledTimes(3);
      expect(getMasterDistributePage).toHaveBeenLastCalledWith(
        action === '搜索'
          ? { pageNo: 1, pageSize: 20, name: '新筛选' }
          : { pageNo: 1, pageSize: 20 },
      );
      expect(state.applied).toEqual(fresh);
    },
  );
  it('轮询在途时切换页码，旧请求完成后补读新页而非展示旧页', async () => {
    vi.useFakeTimers();
    await mountRow(2, 1);
    const pending =
      deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
    const fresh = {
      list: [{ id: 'page-5', blockingRunStatus: 0 }],
      total: 100,
    };
    vi.mocked(getMasterDistributePage)
      .mockResolvedValueOnce({ list: [{ ...state.row }], total: 100 })
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue(fresh);
    await state.query();
    await vi.advanceTimersByTimeAsync(5000);
    state.page.currentPage = 5;
    state.pageChange!();
    await state.query();
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
    pending.resolve({
      list: [{ id: 'page-4', blockingRunStatus: 0 }],
      total: 100,
    });
    await flush();
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({
      pageNo: 5,
      pageSize: 20,
      status: 1,
    });
    expect(state.applied).toEqual(fresh);
  });
  it('仅等待/运行中每5秒刷新，保留当前筛选分页，完成后停止', async () => {
    vi.useFakeTimers();
    await mountRow(1, 1);
    vi.mocked(getMasterDistributePage)
      .mockResolvedValueOnce({ list: [{ ...state.row }], total: 1 })
      .mockResolvedValueOnce({
        list: [{ ...state.row, blockingRunStatus: 0 }],
        total: 1,
      });
    await state.query();
    await vi.advanceTimersByTimeAsync(5000);
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({
      pageNo: 4,
      pageSize: 20,
      status: 1,
    });
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
  });
  it('切走后再返回同一租户，旧发布回执不能触发toast或再次刷新', async () => {
    const shown = ref(true);
    Object.assign(state.row, { status: 0, blockingRunStatus: 0 });
    element = document.createElement('div');
    document.body.append(element);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, () => (shown.value ? h(MasterDistributePage) : null)),
      }),
    );
    app.mount(element);
    const pending = deferred<undefined>();
    vi.mocked(releaseMasterDistribute).mockReturnValueOnce(pending.promise);
    button(element, '发布')!.click();
    shown.value = false;
    await nextTick();
    shown.value = true;
    await flush();
    expect(state.query).toHaveBeenCalledOnce();
    pending.resolve(undefined);
    await flush();
    expect(state.success).not.toHaveBeenCalled();
    expect(state.query).toHaveBeenCalledOnce();
  });
  it('结果未知只提供核查，避免持续无意义轮询', async () => {
    vi.useFakeTimers();
    await mountRow(5);
    vi.mocked(getMasterDistributePage).mockResolvedValue({
      list: [{ ...state.row }],
      total: 1,
    });
    await state.query();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getMasterDistributePage).toHaveBeenCalledOnce();
  });
  it.each(['旧请求先完成', '新请求先完成'])(
    '%s 时VXE只接收最新结果，旧请求不能提前解除loading',
    async (order) => {
      await mountRow(0);
      const old =
        deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
      const fresh =
        deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
      vi.mocked(getMasterDistributePage)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(fresh.promise);
      const first = state.ajaxQuery!();
      const second = state.ajaxQuery!();
      const result = {
        list: [{ id: '9007199254740999', blockingRunStatus: 0 }],
        total: 1,
      };
      const settled = vi.fn();
      let oldSettledBeforeLatest = false;
      void first.then(settled);
      if (order === '旧请求先完成') {
        old.resolve({ list: [{ id: 'old', blockingRunStatus: 2 }], total: 1 });
        await flush();
        oldSettledBeforeLatest = settled.mock.calls.length > 0;
        fresh.resolve(result);
      } else {
        fresh.resolve(result);
        await second;
        old.resolve({ list: [{ id: 'old', blockingRunStatus: 2 }], total: 1 });
      }
      expect(oldSettledBeforeLatest).toBe(false);
      expect(await first).toEqual(result);
      expect(await second).toEqual(result);
    },
  );
  it('切换登录会话清空旧结果并刷新，迟到旧会话响应不能重新写入', async () => {
    await mountRow(0);
    state.formValues = { name: '旧身份搜索' };
    state.submitted = { name: '旧身份搜索' };
    const old =
      deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
    const result = {
      list: [{ id: 'new-session', blockingRunStatus: 0 }],
      total: 1,
    };
    vi.mocked(getMasterDistributePage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue(result);
    const first = state.query();
    state.access.refreshToken = 2;
    await flush();
    expect(state.formValues).toEqual({});
    expect(state.submitted).toEqual({});
    expect(state.setGridOptions).toHaveBeenCalledWith({
      data: [],
      pagerConfig: { total: 0 },
    });
    expect(getMasterDistributePage).toHaveBeenCalledOnce();
    old.resolve({
      list: [{ id: 'old-session', blockingRunStatus: 2 }],
      total: 1,
    });
    await first;
    await flush();
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20 });
    expect(state.applied).toEqual(result);
  });
  it('切走keepalive页面停止轮询，回到页面重新读取状态', async () => {
    vi.useFakeTimers();
    const shown = ref(true);
    element = document.createElement('div');
    document.body.append(element);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, () => (shown.value ? h(MasterDistributePage) : null)),
      }),
    );
    app.mount(element);
    vi.mocked(getMasterDistributePage).mockResolvedValue({
      list: [{ id: 1, blockingRunStatus: 1 }],
      total: 1,
    });
    await state.query();
    shown.value = false;
    await nextTick();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getMasterDistributePage).toHaveBeenCalledOnce();
    shown.value = true;
    await flush();
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
    app.unmount();
    app = undefined;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
  });
  it('初始请求未结束时快速切走再返回，等待VXE完整提交后补读', async () => {
    const shown = ref(true);
    element = document.createElement('div');
    document.body.append(element);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, () => (shown.value ? h(MasterDistributePage) : null)),
      }),
    );
    app.mount(element);
    const old =
      deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
    const result = { list: [{ id: 'fresh', blockingRunStatus: 0 }], total: 1 };
    vi.mocked(getMasterDistributePage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue(result);
    const first = state.query();
    shown.value = false;
    await nextTick();
    shown.value = true;
    await flush();
    expect(getMasterDistributePage).toHaveBeenCalledOnce();
    old.resolve({ list: [{ id: 'old', blockingRunStatus: 2 }], total: 1 });
    await first;
    await flush();
    expect(getMasterDistributePage).toHaveBeenCalledTimes(2);
    expect(state.applied).toEqual(result);
  });
});
async function mountRow(blockingRunStatus: number, status = 0) {
  Object.assign(state.row, { blockingRunStatus, status });
  element = document.createElement('div');
  document.body.append(element);
  app = createApp(MasterDistributePage);
  app.mount(element);
  await nextTick();
  return element;
}
function button(root: HTMLElement, label: string) {
  return [...root.querySelectorAll('button')].find(
    (item) => item.textContent === label,
  );
}
describe('推送类目筛选', () => {
  it('选择类目时带无损编号查询，选择根节点恢复全部任务', async () => {
    const root = await mountRow(0);
    await flush();
    button(root, '主数据目录')!.click();
    await flush();
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({
      pageNo: 1, pageSize: 20, status: 1, categoryId: '9007199254740997',
    });
    button(root, '主数据推送类目')!.click();
    await flush();
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({
      pageNo: 1, pageSize: 20, status: 1,
    });
  });
  it('返回页面后重新读取类目，已删除的选中类目回退到全部任务', async () => {
    const shown = ref(true);
    element = document.createElement('div');
    document.body.append(element);
    app = createApp(defineComponent({
      setup: () => () => h(KeepAlive, () => (shown.value ? h(MasterDistributePage) : null)),
    }));
    app.mount(element);
    await flush();
    button(element, '主数据目录')!.click();
    await flush();
    shown.value = false;
    await nextTick();
    vi.mocked(getMasterPushCategoryTree).mockResolvedValue([]);
    shown.value = true;
    await flush();
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20, status: 1 });
    expect(element.textContent).not.toContain('主数据目录');
  });
  it('分类切换时在途旧请求不能覆盖新的筛选结果', async () => {
    const root = await mountRow(0);
    await flush();
    const old = deferred<PageResult<GovernanceMasterDistributeApi.Distribute>>();
    const fresh = { list: [{ id: 'new-category', blockingRunStatus: 0 }], total: 1 };
    vi.mocked(getMasterDistributePage).mockReturnValueOnce(old.promise).mockResolvedValue(fresh);
    const first = state.query();
    button(root, '主数据目录')!.click();
    await flush();
    old.resolve({ list: [{ id: 'old-category', blockingRunStatus: 0 }], total: 1 });
    await first;
    await flush();
    expect(getMasterDistributePage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20, status: 1, categoryId: '9007199254740997' });
    expect(state.applied).toEqual(fresh);
  });
});

describe('真实主数据派发父页接线', () => {
  it('未知占位显示待核查并禁止执行、发布、编辑和删除', async () => {
    const root = await mountRow(5);
    expect(root.textContent).toContain('结果未知，待核查');
    for (const label of ['手动执行', '发布', '配置', 'common.delete'])
      expect(button(root, label)?.disabled).toBe(true);
    expect(button(root, '执行记录')?.disabled).toBe(false);
    expect(button(root, '发布')?.title).toContain('执行记录');
    expect(button(root, '核查')?.disabled).toBe(false);
  });
  it('详情与配置直接展示，数据库派发保留发布和执行入口', async () => {
    const root = await mountRow(0);
    const direct = root.querySelector('[data-action-group="actions"]');
    expect(
      [...direct!.querySelectorAll('button')].map((item) => item.textContent),
    ).toEqual(['common.detail', '配置', '发布']);
    const dropdown = root.querySelector(
      '[data-action-group="dropDownActions"]',
    );
    expect(
      [...dropdown!.querySelectorAll('button')].map((item) => item.textContent),
    ).toEqual(['执行记录', '手动执行', 'common.delete']);
  });
  it('rEST 启用开关仅调用状态接口，不发布也不立即发送', async () => {
    state.row.distributeType = 2;
    const root = await mountRow(0);
    button(root, '切换启用')!.click();
    await flush();
    expect(updateMasterDistributeStatus).toHaveBeenCalledExactlyOnceWith(
      '9007199254740993',
      1,
    );
    expect(releaseMasterDistribute).not.toHaveBeenCalled();
    expect(handRunMasterDistribute).not.toHaveBeenCalled();
    expect(button(root, '推送')?.disabled).toBe(true);
  });
  it('仅配置权限不能启用任务或修改已启用配置', async () => {
    state.row.distributeType = 2;
    state.canRelease = false;
    const root = await mountRow(0, 1);
    expect(button(root, '切换启用')?.disabled).toBe(true);
    expect(button(root, '配置')?.disabled).toBe(true);
  });
  it.each([2, 5])(
    '已发布任务在执行状态 %s 时仍允许取消发布',
    async (status) => {
      const root = await mountRow(status, 1);
      expect(button(root, '取消发布')?.disabled).toBe(false);
      expect(button(root, '手动执行')?.disabled).toBe(true);
    },
  );
  it('等待中禁止重跑但允许取消发布', async () => {
    const root = await mountRow(1, 1);
    expect(root.textContent).toContain('等待派发');
    expect(button(root, '手动执行')?.disabled).toBe(true);
    expect(button(root, '取消发布')?.disabled).toBe(false);
  });
  it('明确空闲时可执行，超大编号保留为字符串', async () => {
    const root = await mountRow(0);
    expect(root.textContent).not.toContain('结果未知');
    expect(button(root, '手动执行')?.disabled).toBe(false);
    expect(state.row.id).toBe('9007199254740993');
  });
  it('结果未知时取消发布调用取消接口并保留大整数编号', async () => {
    const root = await mountRow(5, 1);
    button(root, '取消发布')!.click();
    await flush();
    expect(cancelMasterDistribute).toHaveBeenCalledWith('9007199254740993');
    expect(releaseMasterDistribute).not.toHaveBeenCalled();
  });
  it('发布请求在途禁止重复提交，完成刷新后解锁', async () => {
    const root = await mountRow(0);
    const pending = deferred<undefined>();
    vi.mocked(releaseMasterDistribute).mockReturnValueOnce(pending.promise);
    button(root, '发布')!.click();
    button(root, '发布')!.click();
    await nextTick();
    expect(releaseMasterDistribute).toHaveBeenCalledOnce();
    expect(button(root, '发布')!.disabled).toBe(true);
    pending.resolve(undefined);
    await flush();
    expect(state.query).toHaveBeenCalledOnce();
    expect(button(root, '发布')!.disabled).toBe(false);
  });
});
