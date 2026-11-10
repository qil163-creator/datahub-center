/* eslint-disable vue/one-component-per-file -- 测试桩用于隔离表单和弹窗 */
import type { App } from 'vue';

import type { LogicTableKind } from '../config';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/logictable';

import LogicTablePage from './logic-table-page.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'one' },
  push: vi.fn(),
  resetTree: vi.fn(),
  confirm: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 'test-user' } }),
}));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/hooks', async (importOriginal) => ({
  ...await importOriginal<typeof import('@vben/hooks')>(),
  getDictOptions: () => [],
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: { handleReset: () => void; handleSubmit: (values: { name: string }) => void }) => {
    const values = reactive({ name: '' });
    const form = defineComponent({
      setup: () => () => h('div', [
        h('input', { placeholder: '请输入表名称', value: values.name, onInput: (event: Event) => { values.name = (event.target as HTMLInputElement).value; } }),
        h('button', { onClick: options.handleReset }, '重置'),
        h('button', { onClick: () => options.handleSubmit({ ...values }) }, '搜索'),
      ]),
    });
    return [form, { reset: async () => { values.name = ''; } }];
  },
}));
vi.mock('@vben/common-ui', async (importOriginal) => ({
  ...await importOriginal<typeof import('@vben/common-ui')>(),
  confirm: harness.confirm,
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.push }),
  useRoute: () => ({ path: '/model/models/test' }),
}));
vi.mock('#/adapter/business-vxe-table', async () => {
  const actions = await import('#/components/table-action');
  return {
    ...actions,
    NativeVxeButton: defineComponent({
      setup(_, { attrs }) { return () => h('button', attrs); },
    }),
  };
});
vi.mock('#/api/modeling/logictable', () => ({
  checkLogicTableDelete: vi.fn(),
  deleteLogicTables: vi.fn(),
  getLogicTableCatTree: vi.fn(),
  getLogicTablePage: vi.fn(),
  getLogicTableSourceSystems: vi.fn(),
  updateLogicTableEnabled: vi.fn(),
}));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn().mockResolvedValue([]) }));
vi.mock('./cat-tree.vue', () => ({
  default: defineComponent({
    emits: ['select'],
    setup(_, { emit, expose }) {
      expose({ reset: harness.resetTree });
      return () => h('button', { onClick: () => emit('select', { type: 'DATA_DOMAIN', dataDomainId: '8' }) }, '测试分类');
    },
  }),
}));
vi.mock('./source-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./summary-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));

let app: App | undefined;
let host: HTMLDivElement;
const flush = async () => { for (let index = 0; index < 16; index++) await Promise.resolve(); await nextTick(); };
function button(text: string) {
  return [...document.querySelectorAll<HTMLButtonElement>('button')].find((item) => item.textContent?.replaceAll(/\s/g, '') === text)!;
}
async function mount(kind: LogicTableKind) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(LogicTablePage, { kind });
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  harness.access = reactive({ accessToken: 'one' });
  harness.confirm.mockResolvedValue(undefined);
  vi.stubGlobal('ResizeObserver', class { disconnect() {} observe() {} });
  vi.mocked(api.getLogicTablePage).mockResolvedValue({ list: [{ id: '123', name: '测试模型', tableNameNotation: 'test', enabled: 0 }], total: 1 });
  vi.mocked(api.getLogicTableCatTree).mockResolvedValue([]);
  vi.mocked(api.getLogicTableSourceSystems).mockResolvedValue([]);
  vi.mocked(api.checkLogicTableDelete).mockResolvedValue({ canDeleteIds: ['123'], canDeleteCount: 1, cannotDeleteCount: 0 });
  vi.mocked(api.deleteLogicTables).mockResolvedValue(true);
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.unstubAllGlobals(); });

describe('逻辑表工具栏', () => {
  it.each(['source', 'dimension', 'fact', 'summary', 'application'] as const)('%s 的列设置与刷新保持一致', async (kind) => {
    await mount(kind);
    host.querySelector<HTMLButtonElement>('[aria-label="个性化设置"]')!.click();
    await flush();
    document.querySelector<HTMLElement>('[aria-label="显示编号"]')!.click();
    button('确定').click();
    await flush();
    expect(host.querySelector('[data-column-key="id"]')).toBeNull();
    host.querySelector<HTMLButtonElement>('[aria-label="刷新"]')!.click();
    await flush();
    expect(host.querySelector('[data-column-key="id"]')).toBeNull();
    app?.unmount(); host.remove();
    await mount(kind);
    expect(host.querySelector('[data-column-key="id"]')).toBeNull();
  });
  it('搜索、重置和批量删除保留现有行为', async () => {
    await mount('dimension');
    button('测试分类').click();
    await flush();
    expect(api.getLogicTablePage).toHaveBeenLastCalledWith(expect.objectContaining({ dataDomainId: '8' }));
    const input = host.querySelector<HTMLInputElement>('input[placeholder="请输入表名称"]')!;
    input.value = '测试'; input.dispatchEvent(new Event('input', { bubbles: true }));
    button('搜索').click();
    await flush();
    expect(api.getLogicTablePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: '测试' }));
    button('重置').click();
    await flush();
    expect(harness.resetTree).toHaveBeenCalled();
    expect(api.getLogicTablePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: undefined, dataDomainId: undefined }));
    host.querySelector<HTMLInputElement>('tbody input[type="checkbox"]')!.click();
    await flush();
    expect(button('批量删除').disabled).toBe(false);
    button('批量删除').click();
    await flush();
    expect(api.checkLogicTableDelete).toHaveBeenCalledWith(['123']);
    expect(api.deleteLogicTables).toHaveBeenCalledWith(['123']);
  });
});
