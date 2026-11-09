/* eslint-disable vue/one-component-per-file -- 页面入口测试使用隔离的展示组件 */
import type { App } from 'vue';

import type { CategoryAdapter, CategoryRow } from './model';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryPage from './page.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
  detailOpen: vi.fn(),
  detailClose: vi.fn(),
  formOpen: vi.fn(),
  formClose: vi.fn(),
  errors: [] as unknown[],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '9' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDate: String }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: container,
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
    Modal: () => null,
    Switch: () => null,
    Tooltip: container,
    message: { warning: vi.fn(), success: vi.fn() },
  };
});
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: () => null,
}));
vi.mock('./reference-icon.vue', () => ({ default: () => null }));
vi.mock('./detail.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.detailOpen, close: harness.detailClose });
      return () => null;
    },
  }),
}));
vi.mock('./form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.formOpen, close: harness.formClose });
      return () => null;
    },
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', slots.actions?.({ row: category })),
    }),
    {
      setGridOptions: vi.fn(),
      grid: { clearCheckboxRow: vi.fn(), setTreeExpand: vi.fn() },
    },
  ],
}));

const category: CategoryRow = {
  id: '9007199254740995',
  parentId: '0',
  key: '9007199254740995',
  name: '类目',
  status: 1,
  sort: 0,
};
const adapter: CategoryAdapter = {
  label: '业务术语类目',
  nameLabel: '业务术语类目名称',
  permissionPrefix: 'governance:category-term',
  list: vi.fn(async () => [category]),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  normalize: (row) => row as CategoryRow,
  toPayload: (row) => row,
};
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 5; index++) await Promise.resolve();
  await nextTick();
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['governance:category-term:query'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.errors = [];
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(CategoryPage, { adapter });
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('类目页面只读详情入口', () => {
  it('仅query权限可打开无损ID详情且不进入编辑表单', () => {
    button('详情')!.click();
    expect(harness.detailOpen).toHaveBeenCalledExactlyOnceWith(
      '9007199254740995',
    );
    expect(harness.formOpen).not.toHaveBeenCalled();
    expect(button('修改')).toBeUndefined();
    expect(harness.errors).toEqual([]);
  });
  it('撤权后已捕获的按钮事件不能再打开详情', async () => {
    const old = button('详情')!;
    harness.access!.accessCodes = [];
    old.click();
    await flush();
    expect(harness.detailOpen).not.toHaveBeenCalled();
    expect(button('详情')).toBeUndefined();
    expect(harness.detailClose).toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });
  it('保留修改与新增下级的原表单事件', async () => {
    harness.access!.accessCodes = [
      'governance:category-term:query',
      'governance:category-term:update',
      'governance:category-term:create',
    ];
    await flush();
    button('修改')!.click();
    expect(harness.formOpen).toHaveBeenLastCalledWith({ id: category.id });
    button('新增')!.click();
    expect(harness.formOpen).toHaveBeenLastCalledWith({
      parentId: category.id,
    });
    expect(harness.detailOpen).not.toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });
});
