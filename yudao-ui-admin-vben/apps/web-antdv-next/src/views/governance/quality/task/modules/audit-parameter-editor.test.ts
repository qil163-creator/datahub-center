/* eslint-disable vue/one-component-per-file -- 使用基础控件替身验证真实参数表单修改。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import AuditEditor from './audit-parameter-editor.vue';

vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('antdv-next', () => {
  const input = defineComponent({
    props: { value: { type: [String, Number], default: undefined }, disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const select = defineComponent({
    props: {
      value: { type: [String, Number, Array], default: undefined },
      options: { type: Array as PropType<{ label: string; value: string }[]>, default: () => [] },
      disabled: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h(
          'select',
          {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit('update:value', (event.target as HTMLSelectElement).value),
          },
          (props.options ?? []).map(
            (option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label),
          ),
        ),
  });
  const container = defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('div', attrs, slots.default?.()),
  });
  return {
    Alert: container,
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Input: input,
    InputNumber: input,
    Select: select,
    RadioGroup: container,
    Checkbox: container,
  };
});
let app: App | undefined;
let host: HTMLDivElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
async function mount(strategyKey: string, initial: Record<string, unknown>) {
  const config = ref(initial);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(AuditEditor, {
        strategyKey,
        config: config.value,
        fieldOptions: [
          { label: '开始值', value: 'start' },
          { label: '结束值', value: 'end' },
        ],
        'onUpdate:config': (value) => {
          config.value = value;
        },
      }),
  });
  app.mount(host);
  await nextTick();
  return config;
}

describe('稽查参数真实交互', () => {
  it('数值逻辑关系通过字段下拉提交结构化条件', async () => {
    const config = await mount('NUMERIC_RELATION_VALIDATION', {
      conditions: [{ leftField: '', operator: '<=', rightField: '' }],
    });
    const selects = [...host!.querySelectorAll('select')];
    selects[0]!.value = 'start';
    selects[0]!.dispatchEvent(new Event('change'));
    await nextTick();
    selects[2]!.value = 'end';
    selects[2]!.dispatchEvent(new Event('change'));
    await nextTick();
    expect(config.value.conditions).toEqual([
      { leftField: 'start', operator: '<=', rightField: 'end' },
    ]);
  });
  it('模型结构表格可添加和修改期望列且没有实际元数据输入', async () => {
    const config = await mount('MODEL_STRUCTURE_VALIDATION', {
      expectedColumns: [{ name: '' }],
    });
    const field = host!.querySelector(
      'input[placeholder="字段名"]',
    ) as HTMLInputElement;
    field.value = 'business_id';
    field.dispatchEvent(new Event('input'));
    await nextTick();
    expect(config.value.expectedColumns).toEqual([{ name: 'business_id' }]);
    [...host!.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '添加模型字段')!
      .click();
    await nextTick();
    expect(config.value.expectedColumns).toEqual([
      { name: 'business_id' },
      { name: '' },
    ]);
    expect(host!.textContent).toContain('实际结构由服务端读取');
    expect(config.value).not.toHaveProperty('actualColumns');
  });
});
