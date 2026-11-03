/* eslint-disable vue/one-component-per-file -- 隔离控件，验证稽查维度筛选及请求竞态。 */
import type { App, PropType } from 'vue';

import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getAuditRuleDimensionList,
  getAuditRulePage,
} from '#/api/governance/auditrule';

import Page from './index.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:audit-rule:query'],
  },
  user: { userInfo: { userId: 'user-a' } },
  grid: { data: [] as GovernanceAuditRuleApi.AuditRule[], loading: false },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('i') }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: String }));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('./modules/preview.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('../standardcommon/standard-list-layout.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.tree?.(), slots.default?.()]),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({ setup: () => () => null }),
  useVbenVxeGrid: () => [
    defineComponent({ setup: () => () => null }),
    {
      setGridOptions: (options: typeof harness.grid) =>
        Object.assign(harness.grid, options),
      grid: {},
    },
  ],
}));
vi.mock('#/api/governance/auditrule', () => ({
  getAuditRuleDimensionList: vi.fn(),
  getAuditRulePage: vi.fn(),
  deleteAuditRule: vi.fn(),
  updateAuditRuleStatus: vi.fn(),
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
    Button: container,
    Spin: container,
    Switch: container,
    Tag: container,
    Tooltip: container,
    Modal: { confirm: vi.fn() },
    message: { success: vi.fn() },
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            onInput: (e: Event) =>
              emit('update:value', (e.target as HTMLInputElement).value),
          }),
    }),
    Tree: defineComponent({
      props: {
        treeData: {
          type: Array as PropType<
            (GovernanceAuditRuleApi.Dimension & {
              children?: GovernanceAuditRuleApi.Dimension[];
            })[]
          >,
          required: true,
        },
      },
      emits: ['select'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            props.treeData.flatMap((root) => [
              h(
                'div',
                { 'data-root': '', onClick: () => emit('select', [root.id]) },
                root.name,
              ),
              ...(root.children ?? []).map((row) =>
                h(
                  'button',
                  {
                    'data-dimension': String(row.id),
                    onClick: () => emit('select', [row.id]),
                  },
                  row.name,
                ),
              ),
            ]),
          ),
    }),
  };
});

let app: App;
let host: HTMLDivElement;
const dimensions = ['准确性', '规范性', '时效性', '一致性', '完整性'].map(
  (name, i) => ({
    id: `900719925474099${i + 3}`,
    name,
    code: `code-${i}`,
    status: 0,
    sort: i + 1,
  }),
);
const settle = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:audit-rule:query'],
  });
  harness.user = reactive({ userInfo: { userId: 'user-a' } });
  harness.grid = { data: [], loading: false };
  vi.mocked(getAuditRuleDimensionList).mockResolvedValue(
    dimensions.toReversed(),
  );
  vi.mocked(getAuditRulePage).mockResolvedValue({
    list: [{ id: 1, name: '原稽查规则' }],
    total: 28,
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
async function mount() {
  app = createApp(Page);
  app.mount(host);
  await settle();
}

describe('稽查规则独立维度', () => {
  it('显示质量维度根和按sort排列的五叶，搜索按name过滤并用无损dimensionId查询', async () => {
    await mount();
    expect(getAuditRuleDimensionList).toHaveBeenCalledTimes(1);
    expect(
      [...host.querySelectorAll('[data-dimension]')].map(
        (node) => node.textContent,
      ),
    ).toEqual(dimensions.map((row) => row.name));
    (
      host.querySelector(
        '[data-dimension="9007199254740993"]',
      ) as HTMLButtonElement
    ).click();
    await settle();
    expect(getAuditRulePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ dimensionId: '9007199254740993', pageNo: 1 }),
    );
    const input = host.querySelector('input')!;
    input.value = '完整';
    input.dispatchEvent(new Event('input'));
    await settle();
    expect(
      [...host.querySelectorAll('[data-dimension]')].map(
        (node) => node.textContent,
      ),
    ).toEqual(['完整性']);
    const root = host.querySelector('[data-root]') as HTMLElement;
    root.click();
    await settle();
    expect(getAuditRulePage).toHaveBeenLastCalledWith(
      expect.objectContaining({ dimensionId: undefined }),
    );
    expect(harness.grid.data[0]?.name).toBe('原稽查规则');
  });

  it('切换维度后旧分页响应不能覆盖当前规则', async () => {
    await mount();
    const old = deferred<{
      list: GovernanceAuditRuleApi.AuditRule[];
      total: number;
    }>();
    vi.mocked(getAuditRulePage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({
        list: [{ id: 2, name: '完整性规则' }],
        total: 1,
      });
    (
      host.querySelector(
        '[data-dimension="9007199254740993"]',
      ) as HTMLButtonElement
    ).click();
    (
      host.querySelector(
        '[data-dimension="9007199254740997"]',
      ) as HTMLButtonElement
    ).click();
    await settle();
    old.resolve({ list: [{ id: 3, name: '迟到的准确性规则' }], total: 1 });
    await settle();
    expect(harness.grid.data[0]?.name).toBe('完整性规则');
  });

  it('权限撤销时清空维度并丢弃未完成的维度响应', async () => {
    const pending = deferred<GovernanceAuditRuleApi.Dimension[]>();
    vi.mocked(getAuditRuleDimensionList).mockReturnValueOnce(pending.promise);
    await mount();
    harness.access.accessCodes = [];
    await settle();
    pending.resolve(dimensions);
    await settle();
    expect(host.querySelectorAll('[data-dimension]')).toHaveLength(0);
    expect(harness.grid.data).toEqual([]);
  });
});
