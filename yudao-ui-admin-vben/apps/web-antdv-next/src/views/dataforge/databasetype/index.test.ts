/* eslint-disable vue/one-component-per-file -- 测试使用轻量组件桩 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Index from './index.vue';

const harness = vi.hoisted(() => ({
  canUpdate: true,
  confirm: vi.fn(),
  page: vi.fn(),
  update: vi.fn(),
  query: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  row: {} as Record<string, unknown>,
  access: { accessToken: 'token', refreshToken: 'refresh' },
  user: { userInfo: { userId: '1' } },
  queryPage: undefined as ((params: unknown, values: unknown) => Promise<unknown>) | undefined,
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.canUpdate }),
}));
vi.mock('@vben/common-ui', () => ({
  confirm: harness.confirm,
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  useVbenModal: () => [defineComponent({ setup: () => () => null }), {}],
}));
vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0, DISABLE: 1 },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/utils', () => ({ isEmpty: () => true }));
vi.mock('antdv-next', () => ({
  message: { success: harness.success, error: harness.error, loading: vi.fn() },
  Switch: defineComponent({
    props: {
      checked: { type: Number, default: 0 },
      disabled: Boolean,
      loading: Boolean,
    },
    emits: ['update:checked'],
    setup:
      (props, { emit }) =>
      () =>
        h('button', {
          disabled: props.disabled || props.loading,
          'data-checked': String(props.checked),
          onClick: () => emit('update:checked', props.checked === 0 ? 1 : 0),
        }),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({ setup: () => () => null }),
  useVbenVxeGrid: (options: { gridOptions: { proxyConfig: { ajax: { query: typeof harness.queryPage } } } }) => {
    harness.queryPage = options.gridOptions.proxyConfig.ajax.query;
    return [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.status?.({ row: harness.row })),
    }),
    { query: harness.query },
    ];
  },
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  deleteDatabaseType: vi.fn(),
  deleteDatabaseTypeList: vi.fn(),
  getDatabaseTypePage: harness.page,
  updateDatabaseTypeStatus: harness.update,
}));
vi.mock('#/components/connection-icon', () => ({
  ConnectionIcon: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('./modules/detail.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));

let app: App;
let host: HTMLDivElement;

async function settle() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.canUpdate = true;
  harness.access = reactive({ accessToken: 'token', refreshToken: 'refresh' });
  harness.user = reactive({ userInfo: { userId: '1' } });
  harness.row = reactive({
    id: '9223372036854775807',
    typeName: 'PostgreSQL',
    status: 0,
    sort: 3,
  });
  harness.confirm.mockImplementation(async (_message, options) => {
    options.beforeClose({ isConfirm: true });
  });
  harness.page.mockResolvedValue({ list: [], total: 0 });
  harness.update.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Index);
  app.mount(host);
});

afterEach(() => {
  app.unmount();
  host.remove();
});

describe('连接器列表状态开关', () => {
  it('确认后仅提交状态，不刷新未筛选的列表', async () => {
    host.querySelector('button')!.click();
    await settle();
    expect(harness.confirm).toHaveBeenCalledWith('确认要停用"PostgreSQL"吗?', expect.any(Object));
    expect(harness.update).toHaveBeenCalledWith({
      id: '9223372036854775807',
      status: 1,
    });
    expect(harness.row.status).toBe(1);
    expect(harness.query).not.toHaveBeenCalled();
  });

  it('取消确认时不请求更新，状态不变', async () => {
    harness.confirm.mockRejectedValueOnce(new Error('取消'));
    host.querySelector('button')!.click();
    await settle();
    expect(harness.update).not.toHaveBeenCalled();
    expect(harness.row.status).toBe(0);
  });

  it('确认后立即发起请求，无需等待弹窗关闭动画', async () => {
    let close!: () => void;
    harness.confirm.mockImplementationOnce((_message, options) => {
      options.beforeClose({ isConfirm: true });
      return new Promise<void>((resolve) => { close = resolve; });
    });
    host.querySelector('button')!.click();
    await settle();
    expect(harness.update).toHaveBeenCalledOnce();
    expect(host.querySelector('button')!.disabled).toBe(true);
    close();
    await settle();
    expect(host.querySelector('button')!.disabled).toBe(false);
  });

  it('已应用状态筛选时切换后刷新列表', async () => {
    await harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, { status: 0 });
    host.querySelector('button')!.click();
    await settle();
    expect(harness.query).toHaveBeenCalledOnce();
  });

  it('查询失败不会覆盖已应用的筛选', async () => {
    await harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, { status: 0 });
    harness.page.mockRejectedValueOnce(new Error('查询失败'));
    await expect(harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, {})).rejects.toThrow();
    host.querySelector('button')!.click();
    await settle();
    expect(harness.query).toHaveBeenCalledOnce();
  });

  it('状态更新期间进行的查询重新读取数据', async () => {
    let resolve!: (value: unknown) => void;
    harness.page.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const query = harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, { status: 0 });
    host.querySelector('button')!.click();
    await settle();
    resolve({ list: [], total: 0 });
    await query;
    expect(harness.page).toHaveBeenCalledTimes(2);
  });

  it('较早查询晚完成时使用最新查询的筛选结果', async () => {
    let resolve!: (value: unknown) => void;
    harness.page.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const older = harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, { status: 0 });
    const latestResult = { list: [{ id: 2 }], total: 1 };
    harness.page.mockResolvedValueOnce(latestResult);
    const latest = harness.queryPage!({ page: { currentPage: 1, pageSize: 20 } }, {});
    await latest;
    resolve({ list: [], total: 0 });
    expect(await older).toEqual(latestResult);
    host.querySelector('button')!.click();
    await settle();
    expect(harness.query).not.toHaveBeenCalled();
  });

  it('登录上下文变化后不回写旧请求的状态', async () => {
    let resolve!: (value: boolean) => void;
    harness.update.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    host.querySelector('button')!.click();
    await settle();
    harness.access.accessToken = '';
    resolve(true);
    await settle();
    expect(harness.row.status).toBe(0);
    expect(harness.success).not.toHaveBeenCalled();
  });

  it('更新失败时保持原状态', async () => {
    harness.update.mockResolvedValueOnce(false);
    host.querySelector('button')!.click();
    await settle();
    expect(harness.row.status).toBe(0);
    expect(harness.error).toHaveBeenCalledWith('更新连接器状态失败');
    expect(harness.query).not.toHaveBeenCalled();
  });

  it('请求抛错时保持原状态并结束加载', async () => {
    harness.update.mockRejectedValueOnce(new Error('请求失败'));
    host.querySelector('button')!.click();
    await settle();
    expect(harness.row.status).toBe(0);
    expect(harness.query).not.toHaveBeenCalled();
    expect(host.querySelector('button')!.disabled).toBe(false);
  });

  it('异步更新期间阻止重复提交', async () => {
    let resolve!: (value: boolean) => void;
    harness.update.mockReturnValueOnce(
      new Promise<boolean>((done) => {
        resolve = done;
      }),
    );
    const button = host.querySelector('button')!;
    button.click();
    await settle();
    expect(button.disabled).toBe(true);
    button.click();
    expect(harness.update).toHaveBeenCalledTimes(1);
    resolve(true);
    await settle();
    expect(button.disabled).toBe(false);
    expect(harness.row.status).toBe(1);
  });

  it('没有更新权限时禁用开关', async () => {
    harness.canUpdate = false;
    app.unmount();
    app = createApp(Index);
    app.mount(host);
    const button = host.querySelector('button')!;
    expect(button.disabled).toBe(true);
    button.click();
    await settle();
    expect(harness.confirm).not.toHaveBeenCalled();
  });
});
