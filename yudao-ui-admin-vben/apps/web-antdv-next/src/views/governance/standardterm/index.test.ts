/* eslint-disable vue/one-component-per-file -- 使用隔离控件验证类目与列表联动。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Page from './index.vue';

const state = vi.hoisted(() => ({
  access: { accessToken: 'a', refreshToken: 'r', loginExpired: false, accessCodes: ['governance:standard-term:query'] },
  user: { userInfo: { userId: '1' }, userRoles: ['reader'] },
  getPage: vi.fn(), getCategories: vi.fn(), modalData: {} as Record<string, unknown>, gridData: [] as unknown[],
}));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access, useUserStore: () => state.user }));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent(() => () => null) }));
vi.mock('@vben/utils', () => ({ isEmpty: (x: unknown[]) => x.length === 0 }));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }),
  confirm: vi.fn(),
  useVbenModal: () => [defineComponent(() => () => null), { setData(data: Record<string, unknown>) { state.modalData = data; return this; }, open() {}, close() {} }],
}));
vi.mock('#/hooks/use-object-detail-query', () => ({ useObjectDetailQuery: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (s: string) => s }));
vi.mock('#/api/governance/standardterm', () => ({ getStandardTermPage: state.getPage, getStandardTermCategories: state.getCategories, deleteStandardTerm: vi.fn(), deleteStandardTermList: vi.fn() }));
vi.mock('./data', () => ({ loadTermUserList: async () => [], useGridColumns: () => [], useGridFormSchema: () => [] }));
vi.mock('./modules/form.vue', () => ({ default: defineComponent(() => () => null) }));
vi.mock('./modules/detail.vue', () => ({ default: defineComponent(() => () => null) }));
vi.mock('../standardcommon/standard-list-layout.vue', () => ({ default: defineComponent({ setup: (_, { slots }) => () => h('div', [slots.tree?.(), slots.default?.()]) }) }));
vi.mock('antdv-next', () => {
  const wrap = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Alert: defineComponent({ props: { message: { type: String, default: '' } }, setup: (props, { slots }) => () => h('div', [props.message, slots.action?.()]) }),
    Button: defineComponent({ setup: (_, { slots, attrs }) => () => h('button', attrs, slots.default?.()) }),
    Input: defineComponent(() => () => h('input')),
    Spin: wrap,
    Tooltip: wrap,
    Tree: defineComponent({
      props: { treeData: { type: Array as PropType<Array<{ children?: unknown[]; key: string; title: string; }>>, default: () => [] } },
      emits: ['select'],
      setup(props, { emit }) {
        function nodes(items: typeof props.treeData): ReturnType<typeof h>[] {
          return items.flatMap((item) => [h('button', { onClick: () => emit('select', [item.key]) }, item.title), ...nodes((item.children ?? []) as typeof props.treeData)]);
        }
        return () => h('div', nodes(props.treeData));
      },
    }),
    message: { success: vi.fn() },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: { ADD: 'add', DELETE: 'delete' },
  TableAction: defineComponent({ props: { actions: { type: Array, default: () => [] } }, setup: (props) => () => h('div', (props.actions as Array<{ label: string; onClick?: () => void }>).map((action) => h('button', { onClick: action.onClick }, action.label))) }),
  useVbenVxeGrid: () => [defineComponent({ setup: (_, { slots }) => () => h('div', [slots['table-title']?.(), slots['toolbar-tools']?.()]) }), {
    grid: { recalculate() {} }, formApi: { getValues: async () => ({}), reset: async () => {} },
    setGridOptions: (options: { data?: unknown[] }) => { state.gridData = options.data ?? []; },
  }],
}));

let app: App;
let host: HTMLDivElement;
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); }
function click(label: string) {
  const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === label);
  expect(button, `缺少按钮 ${label}`).toBeTruthy();
  button!.click();
}

beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'a', refreshToken: 'r', loginExpired: false, accessCodes: ['governance:standard-term:query'] });
  state.user = reactive({ userInfo: { userId: '1' }, userRoles: ['reader'] });
  state.gridData = []; state.modalData = {};
  state.getPage.mockResolvedValue({ list: [], total: 0 });
  state.getCategories.mockResolvedValue([{ id: '9007199254740993', categoryName: '水务' }, { id: '9007199254740994', parentId: '9007199254740993', categoryName: '河流' }]);
  host = document.createElement('div'); document.body.append(host); app = createApp(Page); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });

describe('业务术语树联动', () => {
  it('选择父类后以无损编号筛选，选择根节点恢复全部，新建默认继承所选类目', async () => {
    await flush();
    click('水务'); await flush();
    expect(state.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ termCategoryId: '9007199254740993', pageNo: 1 }));
    click('common.create');
    expect(state.modalData.termCategoryId).toBe('9007199254740993');
    click('业务术语类目'); await flush();
    expect(state.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ termCategoryId: undefined, pageNo: 1 }));
  });
  it('类目读取失败时保留重试入口，重试后显示类目树', async () => {
    state.getCategories.mockRejectedValueOnce(new Error('offline'));
    // 重新挂载以让首次请求使用失败响应。
    app.unmount(); app = createApp(Page); app.mount(host); await flush();
    expect(host.textContent).toContain('术语类目加载失败');
    click('重试'); await flush();
    expect(host.textContent).toContain('水务');
  });
  it('切换身份后旧请求返回不会覆盖新用户列表', async () => {
    await flush();
    let resolveOld!: (value: unknown) => void;
    state.getPage.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    click('水务'); await flush();
    state.getPage.mockResolvedValueOnce({ list: [{ id: 2, name: '新数据' }], total: 1 });
    state.access.accessToken = 'b'; state.user.userInfo.userId = '2'; await flush();
    resolveOld({ list: [{ id: 1, name: '旧数据' }], total: 1 }); await flush();
    expect(state.gridData).toEqual([{ id: 2, name: '新数据' }]);
  });
});
