/* eslint-disable vue/one-component-per-file -- 隔离控件，验证真实稽查表单维度来源和异步回写。 */
import type { App, PropType } from 'vue';

import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getAuditRule,
  getAuditRuleDimensionList,
  getAuditStrategyList,
  updateAuditRule,
} from '#/api/governance/auditrule';

import RuleForm from './form.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: [
      'governance:audit-rule:query',
      'governance:audit-rule:create',
      'governance:audit-rule:update',
    ],
  },
  user: { userInfo: { userId: 'user-a' } },
  confirm: async () => {},
  close: async () => {},
  model: {} as GovernanceAuditRuleApi.AuditRule,
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
  useVbenModal: (config: {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
  }) => {
    const visible = ref(false);
    harness.confirm = config.onConfirm;
    const api = {
      open: () => {
        visible.value = true;
        config.onOpenChange(true);
      },
      close: async () => {
        visible.value = false;
        config.onOpenChange(false);
      },
      lock: vi.fn(),
      unlock: vi.fn(),
      setState: vi.fn(),
    };
    harness.close = api.close;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('section', slots.default?.()) : null,
      }),
      api,
    ];
  },
}));
vi.mock('#/api/governance/auditrule', () => ({
  getAuditRule: vi.fn(),
  getAuditRuleDimensionList: vi.fn(),
  getAuditStrategyList: vi.fn(),
  updateAuditRule: vi.fn(),
  createAuditRule: vi.fn(),
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
    FormItem: container,
    Input: container,
    Select: container,
    Spin: container,
    TextArea: container,
    message: { success: vi.fn(), warning: vi.fn() },
    Form: defineComponent({
      props: {
        model: {
          type: Object as PropType<GovernanceAuditRuleApi.AuditRule>,
          required: true,
        },
      },
      setup: (props, { slots, expose }) => {
        expose({ validate: async () => {} });
        return () => {
          harness.model = props.model;
          return h('form', slots.default?.());
        };
      },
    }),
    TreeSelect: defineComponent({
      props: {
        treeData: {
          type: Array as PropType<GovernanceAuditRuleApi.Dimension[]>,
          required: true,
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            props.treeData.map((row) =>
              h(
                'button',
                {
                  type: 'button',
                  'data-dimension': String(row.id),
                  onClick: () => emit('update:value', row.id),
                },
                row.name,
              ),
            ),
          ),
    }),
  };
});

let app: App;
let host: HTMLDivElement;
const form = ref<InstanceType<typeof RuleForm>>();
const dimensions: GovernanceAuditRuleApi.Dimension[] = [
  {
    id: '9007199254740995',
    name: '完整性',
    code: 'COMPLETENESS',
    status: 0,
    sort: 5,
  },
  {
    id: '9007199254740993',
    name: '准确性',
    code: 'ACCURACY',
    status: 0,
    sort: 1,
  },
];
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
  harness.model = {};
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: [
      'governance:audit-rule:query',
      'governance:audit-rule:create',
      'governance:audit-rule:update',
    ],
  });
  harness.user = reactive({ userInfo: { userId: 'user-a' } });
  vi.mocked(getAuditRuleDimensionList).mockResolvedValue(dimensions);
  vi.mocked(getAuditStrategyList).mockResolvedValue([]);
  vi.mocked(getAuditRule).mockResolvedValue({
    id: '9223372036854775807',
    name: '原规则',
    dimensionId: '9007199254740995',
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(RuleForm, { ref: form }) });
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('稽查规则维度表单', () => {
  it('使用独立维度名称并保留规则ID及所选维度ID', async () => {
    await form.value!.open('9223372036854775807');
    await settle();
    expect(getAuditRule).toHaveBeenCalledWith('9223372036854775807');
    expect(
      [...host.querySelectorAll('[data-dimension]')].map(
        (node) => node.textContent,
      ),
    ).toEqual(['准确性', '完整性']);
    (
      host.querySelector(
        '[data-dimension="9007199254740993"]',
      ) as HTMLButtonElement
    ).click();
    await settle();
    await harness.confirm();
    expect(updateAuditRule).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9223372036854775807',
        dimensionId: '9007199254740993',
      }),
    );
  });

  it('重新打开另一规则时忽略旧详情和旧维度响应', async () => {
    const pending = deferred<GovernanceAuditRuleApi.Dimension[]>();
    vi.mocked(getAuditRuleDimensionList).mockReturnValueOnce(pending.promise);
    vi.mocked(getAuditRule)
      .mockResolvedValueOnce({ id: 1, name: '旧规则', dimensionId: 1 })
      .mockResolvedValueOnce({
        id: 2,
        name: '当前规则',
        dimensionId: '9007199254740995',
      });
    const old = form.value!.open(1);
    await form.value!.open(2);
    pending.resolve([
      { id: 1, name: '旧维度', code: 'OLD', status: 0, sort: 1 },
    ]);
    await old;
    await settle();
    expect(harness.model.name).toBe('当前规则');
    expect(host.textContent).not.toContain('旧维度');
  });

  it.each(['close', 'session'] as const)(
    '%s后丢弃迟到选项，不能保存未完成加载的表单',
    async (reason) => {
      const pending = deferred<GovernanceAuditRuleApi.Dimension[]>();
      vi.mocked(getAuditRuleDimensionList).mockReturnValueOnce(pending.promise);
      const opening = form.value!.open(1);
      await harness.confirm();
      expect(updateAuditRule).not.toHaveBeenCalled();
      if (reason === 'close') await harness.close();
      else harness.access.accessToken = 'actor-b';
      pending.resolve(dimensions);
      await opening;
      await settle();
      expect(host.querySelectorAll('[data-dimension]')).toHaveLength(0);
      expect(harness.model).toEqual({});
    },
  );
});
