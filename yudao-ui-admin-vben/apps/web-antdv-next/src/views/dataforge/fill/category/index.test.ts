/* eslint-disable vue/one-component-per-file -- 通过轻量控件验证分类管理的交互。 */
import { createApp, defineComponent, h, KeepAlive, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Page from './index.vue';

const state = vi.hoisted(() => ({
  access: undefined as any,
  list: vi.fn(),
  get: vi.fn(),
  remove: vi.fn(),
  open: vi.fn(),
  close: vi.fn(),
  modalData: undefined as any,
  query: undefined as any,
  rows: [] as any[],
  expand: vi.fn(),
  custom: vi.fn(),
}));
vi.mock('@vben/stores', () => {
  state.access = reactive({
    accessToken: 'one',
    refreshToken: '',
    accessCodes: ['query', 'create', 'update', 'delete'].map(
      (action) => `dataforge:fill-plan-category:${action}`,
    ),
  });
  return {
    useAccessStore: () => state.access,
    useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: [] }),
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => state.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
  useVbenModal: () => [
    defineComponent({ setup: () => () => null }),
    {
      setData(data: unknown) { state.modalData = data; return this; },
      open: state.open,
      close: state.close,
    },
  ],
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: any) => {
    state.query = options.gridOptions.proxyConfig.ajax.query;
    const gridState = reactive({ data: [] as any[] });
    return [
      defineComponent({
        setup: (_, { slots }) => () => h('div', [
          slots['toolbar-tools']?.(),
          ...gridState.data.map((row) => h('div', { 'data-id': row.id }, [
            row.name, slots.actions?.({ row }),
          ])),
        ]),
      }),
      {
        setGridOptions(value: any) {
          if (value.data) { gridState.data = value.data; state.rows = value.data; }
        },
        grid: { setAllTreeExpand: state.expand, openCustom: state.custom },
      },
    ];
  },
}));
vi.mock('#/api/dataforge/fill', () => ({
  getPlanCategoryList: state.list,
  getPlanCategory: state.get,
  deletePlanCategory: state.remove,
}));
vi.mock('../plan/modules/category-form.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('antdv-next', () => {
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Alert: container, Descriptions: container, DescriptionsItem: container, Tooltip: container,
    Button: defineComponent({ setup: (_, { attrs, slots }) => () => h('button', attrs, slots.default?.()) }),
    Modal: defineComponent({
      props: { open: Boolean },
      emits: ['ok', 'cancel'],
      setup: (props, { emit, slots }) => () => props.open
        ? h('div', { role: 'dialog' }, [slots.default?.(), h('button', { 'data-confirm': '', onClick: () => emit('ok') }, '确定')])
        : null,
    }),
    message: { success: vi.fn() },
  };
});
let app: ReturnType<typeof createApp>;
let host: HTMLDivElement;
const parent = { id: '9007199254740993', parentId: '0', name: '父类目', sort: 0 };
const child = { id: '9007199254740994', parentId: parent.id, name: '水情', sort: 1 };
async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve(); await nextTick(); }
function button(text: string, root: Element = host) {
  return [...root.querySelectorAll('button')].find((node) => node.textContent?.trim() === text)!;
}
beforeEach(() => {
  vi.clearAllMocks();
  state.access.accessCodes = ['query', 'create', 'update', 'delete'].map((action) => `dataforge:fill-plan-category:${action}`);
  state.list.mockResolvedValue([parent, child]);
  state.get.mockResolvedValue(parent);
  state.remove.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Page);
});
afterEach(() => { app?.unmount(); host.remove(); });

describe('数据填报类目管理', () => {
  it('使用现有计划分类数据，搜索子类目时保留父级', async () => {
    app.mount(host);
    await flush();
    await state.query(undefined, { name: '水情' });
    await flush();
    expect(state.rows[0].id).toBe(parent.id);
    expect(state.rows[0].children[0].id).toBe(child.id);
  });
  it('独立权限控制操作并保留大整数 ID', async () => {
    app.mount(host);
    await flush();
    const actions = host.querySelector('[data-id] .qpc-row-actions')!;
    button('修改', actions).click();
    expect(state.modalData).toEqual({ id: parent.id, categoryLabel: '数据填报类目' });
    button('删除', actions).click();
    await flush();
    expect(state.remove).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-confirm]')!.click();
    await flush();
    expect(state.remove).toHaveBeenCalledWith(parent.id);
    expect(state.list).toHaveBeenCalledTimes(2);
  });
  it('仅有查询权限时没有写入操作', async () => {
    state.access.accessCodes = ['dataforge:fill-plan-category:query'];
    app.mount(host);
    await flush();
    const actions = host.querySelector('[data-id] .qpc-row-actions')!;
    expect([...actions.querySelectorAll('button')].map((item) => item.textContent?.trim())).toEqual(['详情']);
    button('详情', actions).click();
    await flush();
    expect(state.get).toHaveBeenCalledWith(parent.id);
    expect(host.textContent).toContain('父类目');
  });
  it('会话权限变化时关闭已打开的类目表单并清空列表', async () => {
    app.mount(host);
    await flush();
    button('新增').click();
    expect(state.open).toHaveBeenCalledOnce();
    state.access.accessCodes = [];
    await flush();
    expect(state.close).toHaveBeenCalledOnce();
    expect(state.rows).toEqual([]);
    expect(state.list).toHaveBeenCalledOnce();
    expect([...host.querySelectorAll('button')].some((item) => item.textContent?.trim() === '新增')).toBe(false);
  });
  it('重置查询可恢复完整类目，展开和列设置仍可操作', async () => {
    app.mount(host);
    await flush();
    await state.query(undefined, { name: '水情' });
    await flush();
    expect(state.expand).toHaveBeenLastCalledWith(true);
    await state.query(undefined, {});
    await flush();
    expect(state.rows[0].children).toHaveLength(1);
    expect(state.expand).toHaveBeenLastCalledWith(false);
    button('展开').click();
    expect(state.expand).toHaveBeenLastCalledWith(true);
    host.querySelector<HTMLButtonElement>('[aria-label="列设置"]')!.click();
    expect(state.custom).toHaveBeenCalledOnce();
  });
  it('缓存页面切回时重新加载类目', async () => {
    const view = reactive({ active: true });
    app = createApp(defineComponent({
      setup: () => () => h(KeepAlive, null, { default: () => view.active ? h(Page) : null }),
    }));
    app.mount(host);
    await flush();
    view.active = false;
    await flush();
    view.active = true;
    await flush();
    expect(state.list).toHaveBeenCalledTimes(2);
  });
});
