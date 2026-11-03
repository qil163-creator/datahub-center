import type { App, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DatabasePage from './index.vue';

const harness = vi.hoisted(() => ({
  confirm: vi.fn(),
  deleteDatabase: vi.fn(),
  hideLoading: vi.fn(),
  getDatabasePage: vi.fn(),
  gridOptions: {} as any,
  query: vi.fn(),
  reload: vi.fn(),
  row: { id: '9007199254740993', name: '生产数据连接' },
}));

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/common-ui', () => ({
  confirm: harness.confirm,
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  useVbenModal: (options: { connectedComponent: { name?: string } }) => [
    (_: unknown, { attrs }: SetupContext) =>
      options.connectedComponent.name === 'CreateWizardStub'
        ? h('button', { onClick: attrs.onSuccess }, '保存完成')
        : null,
    { open: vi.fn(), setData: vi.fn() },
  ],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({}),
  useUserStore: () => ({}),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: vi.fn(),
  isEmpty: () => true,
}));
vi.mock('antdv-next', () => ({
  message: { loading: () => harness.hideLoading, success: vi.fn() },
  Tag: () => null,
  Tooltip: (_: unknown, { slots }: SetupContext) => slots.default?.(),
}));
vi.mock('#/locales', () => ({
  $t: (key: string, params?: unknown[]) =>
    key === 'ui.actionMessage.deleteConfirm'
      ? `确定删除 ${params?.[0]} 吗？`
      : key,
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: { type: Array, default: () => [] },
      dropDownActions: { type: Array, default: () => [] },
    },
    setup(props) {
      return () =>
        h(
          'div',
          (props.dropDownActions ?? []).map((item: any) =>
            h('button', { onClick: item.onClick }, item.label),
          ),
        );
    },
  }),
  useVbenVxeGrid: (options: any) => {
    harness.gridOptions = options.gridOptions;
    return [
      (_: unknown, { slots }: SetupContext) =>
        h('div', slots.actions?.({ row: harness.row })),
      { query: harness.query, reload: harness.reload },
    ];
  },
}));
vi.mock('#/api/dataforge/database', () => ({
  deleteDatabase: harness.deleteDatabase,
  deleteDatabaseList: vi.fn(),
  diagnoseDatabase: vi.fn(),
  getDatabasePage: harness.getDatabasePage,
  updateDatabase: vi.fn(),
}));
vi.mock('./data', () => ({
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('./modules/create-wizard.vue', () => ({
  default: { name: 'CreateWizardStub' },
}));
vi.mock('./modules/diagnosis-modal.vue', () => ({ default: {} }));
vi.mock('./modules/form.vue', () => ({ default: {} }));
vi.mock('./modules/preview.vue', () => ({ default: {} }));
vi.mock('./modules/type-icon-cell.vue', () => ({ default: {} }));
vi.mock('./modules/type-select.vue', () => ({ default: {} }));

let app: App;
let host: HTMLDivElement;

async function mountPage() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DatabasePage);
  app.mount(host);
  await nextTick();
}

function clickDelete() {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent === 'common.delete',
  );
  expect(button).toBeDefined();
  button!.click();
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.deleteDatabase.mockResolvedValue(undefined);
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});

describe('数据连接单条删除', () => {
  it('确认提示包含连接名称，取消时不删除', async () => {
    harness.confirm.mockRejectedValue(new Error('dialog cancelled'));
    await mountPage();
    clickDelete();
    await vi.waitFor(() => expect(harness.confirm).toHaveBeenCalledOnce());
    expect(harness.confirm.mock.calls[0]?.[0]).toContain('生产数据连接');
    expect(harness.deleteDatabase).not.toHaveBeenCalled();
    expect(harness.query).not.toHaveBeenCalled();
  });

  it('确认后按原始无损 ID 删除并刷新', async () => {
    harness.confirm.mockResolvedValue(undefined);
    await mountPage();
    clickDelete();
    await vi.waitFor(() =>
      expect(harness.deleteDatabase).toHaveBeenCalledOnce(),
    );
    expect(harness.deleteDatabase).toHaveBeenCalledWith('9007199254740993');
    await vi.waitFor(() => expect(harness.query).toHaveBeenCalledOnce());
    expect(harness.hideLoading).toHaveBeenCalledOnce();
  });
});

describe('新建连接后刷新', () => {
  it.each(['querySuccess', 'queryError'] as const)(
    '列表请求进行中保存后，在 %s 时补刷第一页并保留筛选',
    async (settled) => {
      let finish!: (value: unknown) => void;
      let fail!: (reason: Error) => void;
      harness.getDatabasePage.mockReturnValueOnce(
        new Promise((resolve, reject) => {
          finish = resolve;
          fail = reject;
        }),
      );
      await mountPage();
      const ajax = harness.gridOptions.proxyConfig.ajax;
      const request = ajax.query(
        { page: { currentPage: 3, pageSize: 20 } },
        { name: '已提交筛选' },
      );
      const saved = [...host.querySelectorAll('button')].find(
        (item) => item.textContent === '保存完成',
      )!;
      saved.click();
      saved.click();
      expect(harness.reload).not.toHaveBeenCalled();

      if (settled === 'querySuccess') {
        finish({ list: [], total: 0 });
        await request;
      } else {
        fail(new Error('列表请求失败'));
        await expect(request).rejects.toThrow('列表请求失败');
      }
      ajax[settled]();
      expect(harness.reload).toHaveBeenCalledOnce();
      expect(harness.reload).toHaveBeenCalledWith();
      expect(harness.getDatabasePage).toHaveBeenCalledWith({
        pageNo: 3,
        pageSize: 20,
        name: '已提交筛选',
      });
    },
  );

  it('列表空闲时保存直接刷新', async () => {
    await mountPage();
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent === '保存完成')!
      .click();
    expect(harness.reload).toHaveBeenCalledOnce();
  });
});
