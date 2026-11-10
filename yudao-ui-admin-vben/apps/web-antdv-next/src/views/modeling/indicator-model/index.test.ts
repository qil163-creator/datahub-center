import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import IndicatorList from './index.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  route: {
    path: '/model/indicator-model/atomic',
    query: {} as Record<string, string>,
  },
  rows: [] as Record<string, unknown>[],
  columns: [] as { field?: string; minWidth?: number; width?: number }[],
  page: vi.fn(),
  status: vi.fn(),
  push: vi.fn(),
  confirm: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  confirm: () => harness.confirm(),
  useVbenModal: () => [
    () => h('div'),
    { setData: () => ({ open: vi.fn() }), close: vi.fn() },
  ],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => h('i'), addIcon: vi.fn() }));
vi.mock('@vben/hooks', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/hooks')>()),
  getDictOptions: () => [],
}));
vi.mock('./modules/use-metric-options', () => ({
  useMetricOptions: () => ({
    domains: ref([]),
    subjects: ref([]),
    models: ref([]),
    loading: ref(false),
    error: ref(false),
    reload: vi.fn(),
    domainLabel: () => '交易',
    subjectLabel: () => '订单',
    userLabel: () => '管理员',
    userPhone: () => '',
  }),
}));
vi.mock('./modules/metric-layout.vue', () => ({
  default: (_: unknown, { slots }: SetupContext) =>
    h('div', [
      slots.guide?.(),
      slots.tree?.(),
      slots.search?.(),
      slots.default?.(),
    ]),
}));
vi.mock('./modules/metric-category-tree.vue', () => ({
  default: () => h('nav'),
}));
vi.mock('antdv-next', () => ({
  Alert: (props: { message: string }) => h('aside', props.message),
  Button: (_: unknown, { slots, attrs }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Input: (props: { value?: string }, { attrs }: SetupContext) =>
    h('input', {
      value: props.value,
      onInput: (event: Event) =>
        (attrs['onUpdate:value'] as (value: string) => void)?.(
          (event.target as HTMLInputElement).value,
        ),
    }),
  Select: () => h('select'),
  Switch: (
    props: { checked?: boolean; disabled?: boolean; loading?: boolean },
    { attrs }: SetupContext,
  ) =>
    h(
      'button',
      {
        class: 'status-switch',
        disabled: props.disabled || props.loading,
        onClick: () => (attrs.onChange as () => void)?.(),
      },
      props.checked ? 'ACTIVE' : 'DRAFT',
    ),
  Tag: (_: unknown, { slots }: SetupContext) => h('span', slots.default?.()),
  Tooltip: (_: unknown, { slots }: SetupContext) =>
    h('span', slots.default?.()),
  TabPane: () => h('div'),
  Tabs: () => h('div'),
  message: { success: vi.fn(), warning: vi.fn() },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: (props: {
    actions?: { disabled?: boolean; label: string; onClick: () => void }[];
  }) =>
    h(
      'div',
      props.actions?.map((action) =>
        h(
          'button',
          { disabled: action.disabled, onClick: action.onClick },
          action.label,
        ),
      ),
    ),
  useVbenVxeGrid: (config: {
    formOptions: {
      handleReset: () => Promise<void>;
      handleSubmit: (values: Record<string, unknown>) => Promise<void>;
    };
    gridOptions: {
      columns: { field?: string; minWidth?: number; width?: number }[];
    };
  }) => {
    const values = reactive<Record<string, unknown>>({});
    harness.columns = config.gridOptions.columns;
    return [
      (_: unknown, { slots }: SetupContext) =>
        h('section', [
          h(
            'form',
            {
              onSubmit: (event: Event) => {
                event.preventDefault();
                void config.formOptions.handleSubmit(values);
              },
            },
            [
              h('input', {
                value: values.keyword,
                onInput: (event: Event) => {
                  values.keyword = (event.target as HTMLInputElement).value;
                },
              }),
              h('button', { type: 'submit' }, '搜索'),
              h(
                'button',
                { type: 'button', onClick: config.formOptions.handleReset },
                '重置',
              ),
            ],
          ),
          slots['toolbar-actions']?.(),
          ...harness.rows.map((row) =>
            h('article', [
              slots.indicator?.({ row }),
              slots.status?.({ row }),
              slots.actions?.({ row }),
            ]),
          ),
        ]),
      {
        grid: {},
        state: { gridOptions: config.gridOptions },
        formApi: {
          reset: () => {
            values.keyword = undefined;
            values.sourceModelId = undefined;
            values.status = undefined;
          },
        },
        setGridOptions: (state: {
          columns?: typeof harness.columns;
          data?: Record<string, unknown>[];
        }) => {
          if (state.columns) {
            harness.columns = state.columns;
            config.gridOptions.columns = state.columns;
          }
          if (state.data)
            harness.rows.splice(0, harness.rows.length, ...state.data);
        },
      },
    ];
  },
}));
vi.mock('#/api/modeling/indicator-model', () => ({
  getIndicatorPage: (...args: unknown[]) => harness.page(...args),
  updateIndicatorStatus: (...args: unknown[]) => harness.status(...args),
  deleteIndicator: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
const row = {
  id: '9223372036854775806',
  revision: '9007199254740993',
  indicatorType: 'ATOMIC',
  name: '支付金额',
  code: 'payment',
  status: 'DRAFT',
  dataDomainId: '1',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['query'],
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  harness.rows = reactive([]);
  harness.page.mockResolvedValue({ list: [row], total: 1 });
  harness.confirm.mockResolvedValue(undefined);
  harness.status.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(IndicatorList, { fixedType: 'ATOMIC' });
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('指标列表交互与请求隔离', () => {
  it('加宽原子指标信息和归属信息列，派生指标保留原有宽度', async () => {
    const widths = () =>
      harness.columns
        .filter((column) =>
          ['name', 'sourceModelName'].includes(column.field ?? ''),
        )
        .map(({ width, minWidth }) => ({ width, minWidth }));
    expect(widths()).toEqual([
      { width: 320, minWidth: undefined },
      { width: 400, minWidth: undefined },
    ]);
    app?.unmount();
    app = createApp(IndicatorList, { fixedType: 'DERIVED' });
    app.mount(host);
    await flush();
    expect(widths()).toEqual([
      { width: undefined, minWidth: 198 },
      { width: undefined, minWidth: 241 },
    ]);
  });
  it('查询按新关键词发送，详情使用完整页并保留大编号', async () => {
    const input = host.querySelector('input')!;
    input.value = ' 退款 ';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    button('搜索').click();
    await flush();
    expect(harness.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        keyword: '退款',
        indicatorType: 'ATOMIC',
        pageSize: 10,
      }),
    );
    button('详情').click();
    expect(harness.push).toHaveBeenCalledWith({
      name: 'ModelingAtomicIndicatorDetail',
      params: { id: row.id },
      query: { listPath: harness.route.path },
    });
  });
  it('重置清空表单筛选并从第一页重新加载', async () => {
    const input = host.querySelector('input')!;
    input.value = '退款';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    button('搜索').click();
    await flush();
    button('重置').click();
    await flush();
    expect(input.value).toBe('');
    expect(harness.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: undefined, pageNo: 1 }),
    );
  });
  it('较早搜索响应不能覆盖较新的筛选结果', async () => {
    const first = deferred<unknown>();
    const last = deferred<unknown>();
    harness.page
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(last.promise);
    button('搜索').click();
    await flush();
    button('搜索').click();
    await flush();
    last.resolve({ list: [{ ...row, name: '最新结果' }], total: 1 });
    await flush();
    first.resolve({ list: [{ ...row, name: '旧结果' }], total: 1 });
    await flush();
    expect(host.textContent).toContain('最新结果');
    expect(host.textContent).not.toContain('旧结果');
  });
  it('状态逐行防重并保留CAS，旧会话完成不会刷新新会话', async () => {
    const pending = deferred<boolean>();
    harness.status.mockReturnValueOnce(pending.promise);
    const control = host.querySelector<HTMLButtonElement>('.status-switch')!;
    control.click();
    await flush();
    control.click();
    await flush();
    expect(harness.status).toHaveBeenCalledOnce();
    expect(harness.status).toHaveBeenCalledWith({
      id: row.id,
      expectedRevision: row.revision,
      status: 'ACTIVE',
    });
    harness.access.accessToken = 'session-b';
    await flush();
    const requests = harness.page.mock.calls.length;
    pending.resolve(true);
    await flush();
    expect(harness.page).toHaveBeenCalledTimes(requests);
  });
  it('确认期间普通卸载不会写入旧对象', async () => {
    const confirmation = deferred<undefined>();
    harness.confirm.mockReturnValueOnce(confirmation.promise);
    host.querySelector<HTMLButtonElement>('.status-switch')!.click();
    await flush();
    app?.unmount();
    app = undefined;
    confirmation.resolve(undefined);
    await flush();
    expect(harness.status).not.toHaveBeenCalled();
  });
  it('卸载后迟到的写响应不会刷新已销毁的列表', async () => {
    const writing = deferred<boolean>();
    harness.status.mockReturnValueOnce(writing.promise);
    host.querySelector<HTMLButtonElement>('.status-switch')!.click();
    await flush();
    expect(harness.status).toHaveBeenCalledOnce();
    const count = harness.page.mock.calls.length;
    app?.unmount();
    app = undefined;
    writing.resolve(true);
    await flush();
    expect(harness.page).toHaveBeenCalledTimes(count);
  });
});
