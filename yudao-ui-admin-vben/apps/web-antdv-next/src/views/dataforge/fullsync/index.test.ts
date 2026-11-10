/* eslint-disable vue/one-component-per-file -- 使用组件替身验证列表网格与批量操作。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import FullSync from './index.vue';

const harness = vi.hoisted(() => ({
  options: undefined as any,
  confirm: undefined as any,
  page: vi.fn(),
  remove: vi.fn(),
  clear: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent({ setup: () => () => h('i') }) }));
vi.mock('@vben/utils', () => ({ formatDate: () => '-' }));
vi.mock('./use-scope', () => ({
  useFullSyncScope: () => ({ active: ref(true), scope: ref('session') }),
}));
vi.mock('#/hooks/use-object-request', () => ({
  useObjectRequest: () => ({ begin: () => () => true }),
}));
vi.mock('#/api/dataforge/fullsync', () => ({
  LIST_PATH: '/full-sync',
  getWizardPage: harness.page,
  deleteWizard: harness.remove,
  getDatabaseOptions: vi.fn(async () => []),
  getResponsibleUsers: vi.fn(async () => []),
  getWizard: vi.fn(),
  saveWizardStep: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  const Button = defineComponent({
    props: { disabled: Boolean },
    setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()),
  });
  return {
    Alert: Container, Button, Dropdown: Container, Input: Container, Menu: Container,
    MenuItem: Button, Select: Container, Switch: Container, Tooltip: Container,
    message: { success: vi.fn() },
    Modal: { confirm: (options: any) => { harness.confirm = options; } },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: { ADD: 'add', DELETE: 'delete' },
  NativeVxeButton: defineComponent({ setup: () => () => h('button') }),
  TableAction: defineComponent({
    props: { actions: { type: Array, required: true } },
    setup: (props) => () => props.actions.filter((action: any) => action.ifShow !== false).map((action: any) => h('button', { disabled: action.disabled, onClick: action.onClick }, action.label)),
  }),
  useVbenVxeGrid: (options: any) => {
    harness.options = options;
    return [
      defineComponent({
        setup: (_, { slots }) => () => h('div', [
          h('div', { class: 'test-toolbar' }, slots['toolbar-tools']?.()),
          slots.pager?.(),
        ]),
      }),
      { grid: { clearCheckboxRow: harness.clear }, setLoading: vi.fn() },
    ];
  },
}));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('./modules/reference-icon.vue', () => ({
  default: defineComponent({ setup: () => () => h('i') }),
}));

let app: App;
let host: HTMLDivElement;
const row = { id: '9007199254740993', revision: 7, status: 'DRAFT', config: { taskName: '测试' } };

beforeEach(async () => {
  vi.clearAllMocks();
  harness.page.mockResolvedValue({ list: [row], total: 1 });
  harness.remove.mockResolvedValue(undefined);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(FullSync);
  app.mount(host);
  for (let i = 0; i < 4; i++) { await Promise.resolve(); await nextTick(); }
});
afterEach(() => { app.unmount(); host.remove(); });

describe('整库同步列表工具栏', () => {
  it('使用真实网格的列设置和刷新，并按状态限制复选框', async () => {
    const { gridOptions } = harness.options;
    expect(gridOptions.toolbarConfig.custom).toBe(true);
    expect(gridOptions.toolbarConfig.zoom).toBe(false);
    expect(gridOptions.columns.find((column: any) => column.field === 'actions').fixed).toBe('right');
    expect(gridOptions.checkboxConfig.checkMethod({ row })).toBe(true);
    expect(gridOptions.checkboxConfig.checkMethod({ row: { ...row, status: 'RUNNING' } })).toBe(false);
    const previous = harness.page.mock.calls.length;
    await gridOptions.toolbarConfig.refreshOptions.queryMethod();
    expect(harness.page).toHaveBeenCalledTimes(previous + 1);
    expect(harness.page.mock.lastCall?.[0]).toMatchObject({ pageNo: 1, pageSize: 10 });
  });

  it('保留无损编号的选中删除与成功后清空选择', async () => {
    const deleteButton = [...host.querySelectorAll('button')].find((button) => button.textContent?.includes('删除'))!;
    expect(deleteButton.disabled).toBe(true);
    harness.options.gridEvents.checkboxChange({ records: [row] });
    await nextTick();
    expect(deleteButton.disabled).toBe(false);
    deleteButton.click();
    await harness.confirm.onOk();
    await nextTick();
    expect(harness.remove).toHaveBeenCalledWith(row.id, row.revision);
    expect(harness.clear).toHaveBeenCalled();
    expect(deleteButton.disabled).toBe(true);
  });
});
