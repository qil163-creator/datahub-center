/* eslint-disable vue/one-component-per-file -- 隔离基础控件，验证字段编辑器真实的精度绑定。 */
import type { App } from 'vue';

import type { LogicFieldRow } from '../editor-state';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { logicFieldPayload, newLogicField, validateLogicFields } from '../editor-state';
import FieldGrid from './field-grid.vue';

vi.mock('../icons', () => ({ QdIcon: () => null }));
vi.mock('antdv-next', () => {
  const plain = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Checkbox: plain,
    Input: plain,
    Modal: { confirm: vi.fn() },
    Select: plain,
    Tag: plain,
    InputNumber: defineComponent({
      props: { value: { type: Number, default: undefined }, disabled: Boolean },
      emits: ['update:value'],
      setup: (props, { emit }) => () => h('input', {
        value: props.value,
        disabled: props.disabled,
        onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value)),
      }),
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('逻辑字段精度编辑', () => {
  it.each(['DECIMAL', 'NUMERIC', 'NUMBER'])('%s 输入总精度后写入真正的精度字段，保持旧字符长度原值', async (dataType) => {
    const rows = ref<LogicFieldRow[]>([newLogicField({
      fieldUid: 'amount', fieldName: 'amount', cnName: '金额', dataType,
      dataLength: 60, dataPrecision: 18, dataScale: 2,
    })]);
    host = document.createElement('div');
    document.body.append(host);
    app = createApp({ setup: () => () => h(FieldGrid, {
      kind: 'application', modelValue: rows.value,
      'onUpdate:modelValue': (value) => { rows.value = value; },
    }) });
    app.mount(host);
    await nextTick();
    const precision = host.querySelector<HTMLInputElement>('input[aria-label="总精度"]')!;
    expect(precision.value).toBe('18');
    precision.value = '38';
    precision.dispatchEvent(new Event('input', { bubbles: true }));
    await nextTick();
    expect(rows.value[0]).toMatchObject({ dataLength: 60, dataPrecision: 38, dataScale: 2 });
    expect(validateLogicFields(rows.value)).toBe('');
    expect(logicFieldPayload(rows.value)[0]).toMatchObject({ dataPrecision: 38, dataScale: 2 });
  });
});
