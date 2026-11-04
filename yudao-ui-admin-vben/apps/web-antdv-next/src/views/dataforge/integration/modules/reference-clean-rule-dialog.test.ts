/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身仅模拟本组件依赖的输入事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/cleanrule';

import ReferenceCleanRuleDialog from './reference-clean-rule-dialog.vue';

vi.mock('#/api/governance/cleanrule', () => ({
  createCleanRule: vi.fn(async () => 9_007_199_254_740_993n),
}));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: {
      ariaLabel: String,
      disabled: Boolean,
      value: [String, Number, Boolean, Array],
    },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          'aria-label': (attrs['aria-label'] as string) ?? props.ariaLabel,
          disabled: props.disabled,
          value: props.value as string,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const Passthrough = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', [slots.title?.(), slots.default?.(), slots.footer?.()]),
  });
  return {
    Button: defineComponent({
      emits: ['click'],
      setup:
        (_props, { emit, slots }) =>
        () =>
          h('button', { onClick: () => emit('click') }, slots.default?.()),
    }),
    DatePicker: Field,
    Input: Field,
    InputNumber: Field,
    Modal: Passthrough,
    RadioGroup: defineComponent({
      props: { options: Array, value: [String, Number, Boolean] },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            (props.options as { label: string; value: unknown }[]).map(
              (option) =>
                h(
                  'button',
                  {
                    'data-checked': String(option.value === props.value),
                    onClick: () => emit('update:value', option.value),
                  },
                  option.label,
                ),
            ),
          ),
    }),
    Select: Field,
    TextArea: Field,
    message: { warning: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.clearAllMocks();
});
const rule = (extra: Record<string, unknown> = {}) => ({
  categoryId: 5,
  code: '039',
  columnBinding: 'SINGLE' as const,
  config: {},
  id: 1,
  name: '清理过期记录',
  ruleLevel: 1,
  strategyKey: 'CHECK_EXPIRATION',
  ...extra,
});
function mount(selected: ReturnType<typeof rule>) {
  host = document.createElement('div');
  document.body.append(host);
  const events: unknown[] = [];
  const state = reactive({ rule: selected });
  app = createApp({
    render: () =>
      h(ReferenceCleanRuleDialog, {
        inputFields: [{ name: 'STCD' }, { name: 'RVCD' }],
        onBack: () => events.push('back'),
        onClose: () => events.push('close'),
        onConfirm: (value: unknown) => events.push(value),
        open: true,
        rule: state.rule as never,
      }),
  });
  app.mount(host);
  return events;
}
async function type(label: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  expect(field).toBeTruthy();
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
  expect(button).toBeTruthy();
  button.click();
  await nextTick();
  await Promise.resolve();
}

describe('清洗规则实例对话框', () => {
  it('需要清洗名称与字段，创建治理规则后以十进制字符串 ID 和字段绑定回传', async () => {
    const events = mount(rule());
    expect(host.textContent).toContain('新增清洗规则–清理过期记录');
    await click('确定');
    expect(api.createCleanRule).not.toHaveBeenCalled();
    await type('清洗名称', '清理三十天前记录');
    await click('确定');
    expect(api.createCleanRule).not.toHaveBeenCalled();
    await type('清洗字段', 'STCD');
    await click('确定');
    await nextTick();
    expect(api.createCleanRule).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(api.createCleanRule).mock.calls[0]![0] as Record<string, unknown>;
    expect(payload).toMatchObject({
      categoryId: 5,
      code: '039',
      name: '清理三十天前记录',
      ruleLevel: 1,
      status: 0,
      strategyKey: 'CHECK_EXPIRATION',
    });
    expect(payload.config).toMatchObject({ dataRange: 0, dataRangeType: 1, dataRangeValue: 30, handleType: 1 });
    expect(events).toEqual([{ field: 'STCD', ruleId: '9007199254740993' }]);
  });

  it('标记为过期时必须填写标记字段和标记值，返回与关闭不创建规则', async () => {
    const events = mount(rule());
    await type('清洗名称', '标记过期');
    await type('清洗字段', 'STCD');
    await click('标记为过期');
    await click('确定');
    expect(api.createCleanRule).not.toHaveBeenCalled();
    await type('标记字段', 'RVCD');
    await type('标记值', 'expired');
    await click('确定');
    expect(vi.mocked(api.createCleanRule).mock.calls[0]![0].config).toMatchObject({
      handleColumns: 'RVCD',
      handleType: 0,
      handleValue: 'expired',
    });
    events.length = 0;
    await click('返回');
    await click('关闭');
    expect(events).toEqual(['back', 'close']);
  });

  it('整行规则不需要清洗字段，枚举映射规则逐行维护原值与标准值', async () => {
    const events = mount(
      rule({ columnBinding: 'ALL', name: '枚举值映射标准化', strategyKey: 'MENU_CUSTOM' }),
    );
    expect(host.querySelector('[aria-label="清洗字段"]')).toBeNull();
    await type('清洗名称', '省市映射');
    await click('确定');
    expect(api.createCleanRule).not.toHaveBeenCalled();
    await click('新增规则');
    await type('第1行原值', '0');
    await type('第1行标准值', '省');
    await click('确定');
    expect(vi.mocked(api.createCleanRule).mock.calls[0]![0].config).toEqual({
      stringValue: [{ name: '省', value: '0' }],
    });
    expect(events).toEqual([{ ruleId: '9007199254740993' }]);
  });
});
