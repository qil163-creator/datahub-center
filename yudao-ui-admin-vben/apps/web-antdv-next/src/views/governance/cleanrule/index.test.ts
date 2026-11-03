/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 最小组件替身用于验证类目 ID 和请求归属。 */
import type { App } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  onBeforeUnmount,
  reactive,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getCategoryCleanRuleList } from '#/api/governance/categorycleanrule';
import { deleteCleanRule, getCleanRulePage } from '#/api/governance/cleanrule';

import Page from './index.vue';

const harness = vi.hoisted(() => ({
  open: vi.fn(),
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '7' } },
  destroyDialog: vi.fn(),
  closeTest: vi.fn(),
  confirm: undefined as undefined | { onOk: () => Promise<void> },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.open });
      return () => null;
    },
  }),
}));
vi.mock('./modules/test.vue', () => ({
  default: defineComponent({
    setup: () => {
      onBeforeUnmount(harness.closeTest);
      return () => null;
    },
  }),
}));
vi.mock('#/api/governance/categorycleanrule', () => ({
  getCategoryCleanRuleList: vi.fn(),
}));
vi.mock('#/api/governance/cleanrule', () => ({
  getCleanRulePage: vi.fn(),
  deleteCleanRule: vi.fn(),
  updateCleanRuleStatus: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/utils', () => ({
  handleTree: (rows: unknown[]) => rows,
  formatDateTime: () => '-',
}));
vi.mock('../standardcommon/standard-list-layout.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.tree?.(), slots.default?.()]),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: {
    formOptions: {
      handleReset: () => Promise<void>;
      handleSubmit: (values: Record<string, unknown>) => Promise<void>;
      handleValuesChange: (values: Record<string, unknown>) => void;
      schema: {
        componentProps?: { placeholder?: string };
        fieldName: string;
      }[];
    };
    gridOptions: { data: unknown[]; loading?: boolean };
  }) => {
    const state = reactive({ ...options.gridOptions });
    const values = reactive<Record<string, unknown>>({});
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', [
              ...options.formOptions.schema.map((field) =>
                h('input', {
                  placeholder: field.componentProps?.placeholder,
                  value: values[field.fieldName],
                  onInput: (event: Event) => {
                    values[field.fieldName] = (
                      event.target as HTMLInputElement
                    ).value;
                    options.formOptions.handleValuesChange(values);
                  },
                }),
              ),
              h(
                'button',
                { onClick: () => options.formOptions.handleSubmit(values) },
                '查询',
              ),
              h(
                'button',
                { onClick: options.formOptions.handleReset },
                '重置',
              ),
              slots['toolbar-tools']?.(),
              h('pre', { 'data-rows': true }, JSON.stringify(state.data)),
              h('span', { 'data-loading': true }, String(state.loading)),
              ...state.data.map((row) => slots.actions?.({ row })),
            ]),
      }),
      {
        formApi: {
          reset: async () => {
            for (const field of options.formOptions.schema) {
              values[field.fieldName] = undefined;
            }
            options.formOptions.handleValuesChange(values);
          },
        },
        setGridOptions: (next: Partial<typeof state>) =>
          Object.assign(state, next),
      },
    ];
  },
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
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Input: defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    Select: container,
    Switch: container,
    Tag: container,
    Tooltip: container,
    message: { success: vi.fn() },
    Modal: {
      confirm: (options: { onOk: () => Promise<void> }) => {
        harness.confirm = options;
        return { destroy: harness.destroyDialog };
      },
    },
    Tree: defineComponent({
      props: ['treeData', 'selectedKeys'],
      emits: ['select'],
      setup:
        (props, { emit }) =>
        () =>
          h('div', [
            h('pre', { 'data-tree': true }, JSON.stringify(props.treeData)),
            h(
              'pre',
              { 'data-selected': true },
              JSON.stringify(props.selectedKeys),
            ),
            ...props.treeData.map(
              (item: { categoryName: string; id: number | string }) =>
                h(
                  'button',
                  { onClick: () => emit('select', [item.id]) },
                  item.categoryName,
                ),
            ),
          ]),
    }),
  };
});
let app: App;
let host: HTMLDivElement;
async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '7' } });
  harness.confirm = undefined;
  vi.mocked(getCategoryCleanRuleList).mockResolvedValue([
    {
      id: '9007199254740993',
      parentId: '9007199254740992',
      categoryName: '大编号类目',
      remark: '',
    },
    { id: 7, parentId: 0, categoryName: '普通类目', remark: '' },
  ]);
  vi.mocked(getCleanRulePage).mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Page);
  app.directive('access', {});
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('清洗规则类目 ID 入口', () => {
  it('树 ID 与 parentId 使用无损字符串，筛选和新增传入原始大 ID', async () => {
    await flush();
    expect(host.querySelector('[data-tree]')!.textContent).toContain(
      '"id":"9007199254740993","parentId":"9007199254740992"',
    );
    expect(host.querySelector('[data-tree]')!.textContent).toContain(
      '"id":"7","parentId":"0"',
    );
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent === '大编号类目')!
      .click();
    await flush();
    expect(getCleanRulePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ categoryId: '9007199254740993' }),
    );
    expect(host.querySelector('[data-selected]')!.textContent).toBe(
      '["9007199254740993"]',
    );
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === '新增')!
      .click();
    expect(harness.open).toHaveBeenCalledWith(undefined, '9007199254740993');
  });
  it.each(['old-first', 'new-first'])(
    '快速类目选择只有当前列表可回写，旧 finally 不清新 loading：%s',
    async (order) => {
      await flush();
      let old!: (result: {
        list: { id: string; name: string }[];
        total: number;
      }) => void;
      let current!: (result: {
        list: { id: string; name: string }[];
        total: number;
      }) => void;
      vi.mocked(getCleanRulePage)
        .mockReturnValueOnce(
          new Promise((resolve) => {
            old = resolve;
          }),
        )
        .mockReturnValueOnce(
          new Promise((resolve) => {
            current = resolve;
          }),
        );
      [...host.querySelectorAll('button')]
        .find((item) => item.textContent === '大编号类目')!
        .click();
      [...host.querySelectorAll('button')]
        .find((item) => item.textContent === '普通类目')!
        .click();
      await flush();
      const oldResult = {
        list: [{ id: '1', name: '旧类目私有规则' }],
        total: 99,
      };
      const newResult = { list: [{ id: '2', name: '当前类目规则' }], total: 1 };
      if (order === 'old-first') {
        old(oldResult);
      } else {
        current(newResult);
      }
      await flush();
      expect(host.querySelector('[data-loading]')!.textContent).toBe(
        order === 'old-first' ? 'true' : 'false',
      );
      expect(host.querySelector('[data-rows]')!.textContent).toBe(
        JSON.stringify(order === 'old-first' ? [] : newResult.list),
      );
      if (order === 'old-first') current(newResult);
      else old(oldResult);
      await flush();
      expect(host.querySelector('[data-rows]')!.textContent).toContain(
        '当前类目规则',
      );
      expect(host.querySelector('[data-rows]')!.textContent).not.toContain(
        '旧类目',
      );
      expect(host.querySelector('[data-loading]')!.textContent).toBe('false');
    },
  );
  it.each(['session', 'visit-session', 'user'])(
    '身份切换清空并重载列表与树，拒绝旧身份响应：%s',
    async (change) => {
      app.unmount();
      let oldRows!: (result: {
        list: { id: string; name: string }[];
        total: number;
      }) => void;
      let oldCategories!: (
        result: { categoryName: string; id: string; remark: string }[],
      ) => void;
      vi.mocked(getCleanRulePage)
        .mockReturnValueOnce(
          new Promise((resolve) => {
            oldRows = resolve;
          }),
        )
        .mockResolvedValue({
          list: [{ id: '9', name: '新身份规则' }],
          total: 1,
        });
      vi.mocked(getCategoryCleanRuleList)
        .mockReturnValueOnce(
          new Promise((resolve) => {
            oldCategories = resolve;
          }),
        )
        .mockResolvedValue([
          { id: '9', categoryName: '新身份类目', remark: '' },
        ]);
      app = createApp(Page);
      app.directive('access', {});
      app.mount(host);
      if (change === 'session') harness.access.accessToken = '2';
      else if (change === 'visit-session') harness.access.refreshToken = '2';
      else harness.user.userInfo.userId = '8';
      await flush();
      oldRows({ list: [{ id: '1', name: '旧身份私有规则' }], total: 8 });
      oldCategories([{ id: '1', categoryName: '旧身份私有类目', remark: '' }]);
      await flush();
      expect(host.querySelector('[data-rows]')!.textContent).toContain(
        '新身份规则',
      );
      expect(host.querySelector('[data-tree]')!.textContent).toContain(
        '新身份类目',
      );
      expect(host.textContent).not.toContain('旧身份');
      expect(host.querySelector('[data-selected]')!.textContent).toBe('[]');
      expect(getCleanRulePage).toHaveBeenLastCalledWith(
        expect.objectContaining({ pageNo: 1, categoryId: undefined }),
      );
      expect(harness.closeTest).toHaveBeenCalled();
    },
  );
  it('查询输入变化后旧查询快照不可回写，即使尚未再次发送请求', async () => {
    await flush();
    let finish!: (result: {
      list: { id: string; name: string }[];
      total: number;
    }) => void;
    vi.mocked(getCleanRulePage).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === '查询')!
      .click();
    const field = host.querySelector<HTMLInputElement>(
      'input[placeholder="请输入规则名称"]',
    )!;
    field.value = '当前输入';
    field.dispatchEvent(new Event('input'));
    finish({ list: [{ id: '1', name: '旧查询规则' }], total: 5 });
    await flush();
    expect(host.querySelector('[data-rows]')!.textContent).toBe('[]');
    expect(host.querySelector('[data-loading]')!.textContent).toBe('false');
    expect(host.textContent).not.toContain('旧查询规则');
  });
  it('卸载使列表与树响应失效，不触发后续请求', async () => {
    app.unmount();
    let rows!: (result: { list: { id: string }[]; total: number }) => void;
    let categories!: (
      result: { categoryName: string; id: string; remark: string }[],
    ) => void;
    vi.mocked(getCleanRulePage).mockReturnValueOnce(
      new Promise((resolve) => {
        rows = resolve;
      }),
    );
    vi.mocked(getCategoryCleanRuleList).mockReturnValueOnce(
      new Promise((resolve) => {
        categories = resolve;
      }),
    );
    app = createApp(Page);
    app.directive('access', {});
    app.mount(host);
    app.unmount();
    const requests = vi.mocked(getCleanRulePage).mock.calls.length;
    rows({ list: [{ id: '1' }], total: 1 });
    categories([{ id: '1', categoryName: '迟到类目', remark: '' }]);
    await flush();
    expect(host.querySelector('main')).toBeNull();
    expect(getCleanRulePage).toHaveBeenCalledTimes(requests);
  });
  it('旧身份删除确认被销毁，迟到确认不能在新会话删除同 ID', async () => {
    app.unmount();
    vi.mocked(getCleanRulePage).mockResolvedValue({
      list: [{ id: '1', name: '待删除规则' }],
      total: 1,
    });
    app = createApp(Page);
    app.directive('access', {});
    app.mount(host);
    await flush();
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === '删除')!
      .click();
    const oldDialog = harness.confirm!;
    harness.access.refreshToken = '2';
    await flush();
    expect(harness.destroyDialog).toHaveBeenCalledTimes(1);
    await oldDialog.onOk();
    expect(deleteCleanRule).not.toHaveBeenCalled();
  });
});
