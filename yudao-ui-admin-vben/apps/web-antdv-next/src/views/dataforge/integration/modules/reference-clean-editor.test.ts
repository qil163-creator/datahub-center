/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身仅模拟本页输入与规则弹窗。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { getCleanRulePage } from '#/api/governance/cleanrule';

import ReferenceCleanEditor from './reference-clean-editor.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('#/api/governance/cleanrule', () => ({
  getCleanRulePage: vi.fn(async () => ({ list: [], total: 0 })),
}));
vi.mock('antdv-next', () => {
  const Input = defineComponent({
    props: { value: String, disabled: Boolean },
    emits: ['update:value'],
    setup: (props, { emit }) => () => h('input', {
      value: props.value,
      disabled: props.disabled,
      onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
    }),
  });
  return {
    Input,
    TextArea: Input,
    Select: defineComponent({ setup: () => () => h('select') }),
    Alert: defineComponent({ props: { message: String }, setup: (props) => () => h('aside', props.message) }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Modal: defineComponent({
      props: { open: Boolean },
      setup: (props, { slots }) => () => props.open ? h('div', { role: 'dialog' }, slots.default?.()) : null,
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.clearAllMocks();
});
function mount(config: Record<string, any>) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled: false });
  app = createApp({ render: () => h(ReferenceCleanEditor, {
    ...state,
    inputFields: [{ name: 'code', dataType: 'STRING' }],
  }) });
  app.mount(host);
  return state;
}
async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}

describe('转换组件主要清洗配置', () => {
  it('删除本地执行面板但保留历史参数，where条件仍可编辑', async () => {
    const state = mount({
      executionMode: 'LOCAL',
      enableFormatting: true,
      formatRules: [{ fieldName: 'code', type: 'TRIM' }],
      fieldsToRemove: 'old',
      unknown: 42,
      governanceRules: [],
    });
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toContain('执行方式与本地清洗配置');
    const where = host.querySelector<HTMLInputElement>('input[placeholder="请输入where条件"]')!;
    where.value = 'id > 10';
    where.dispatchEvent(new Event('input'));
    await nextTick();
    expect(state.config).toEqual({
      executionMode: 'LOCAL', enableFormatting: true,
      formatRules: [{ fieldName: 'code', type: 'TRIM' }],
      fieldsToRemove: 'old', unknown: 42,
      governanceRules: [], whereCondition: 'id > 10',
    });
    expect(state.config.defaultValues).toBeUndefined();
    expect(state.config.deduplicationStrategy).toBeUndefined();
  });

  it('主要规则选择器仍加载治理规则并保存无损编号', async () => {
    vi.mocked(getCleanRulePage).mockResolvedValue({
      list: [{
        id: '9007199254740993', name: '去除空格', status: 0,
        ruleLevel: 1, strategyKey: 'REMOVE_WHITESPACE',
        engineSupported: true, rowTransformSupported: true,
        config: { mode: 'TRIM' },
      }],
      total: 1,
    });
    const state = mount({ unknown: 42 });
    const add = [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '新增规则')!;
    add.click();
    await flush();
    const card = host.querySelector<HTMLButtonElement>('.qi-clean-rule-cards button')!;
    expect(card.textContent).toContain('去除空格');
    card.click();
    await nextTick();
    expect(state.config.executionMode).toBe('GOVERNANCE');
    expect(state.config.governanceRules).toMatchObject([{ ruleId: '9007199254740993' }]);
    expect(state.config.unknown).toBe(42);
  });
});
