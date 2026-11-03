import type { App } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ParameterEditor from './parameter-editor.vue';

const harness = vi.hoisted(() => ({ nextValue: undefined as unknown }));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({ render: () => null }),
  Button: defineComponent({ render: () => null }),
  Checkbox: defineComponent({ render: () => null }),
  Input: defineComponent({ render: () => null }),
  InputNumber: defineComponent({ render: () => null }),
  Select: defineComponent({
    props: ['options'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'button',
          {
            'data-kind': props.options.length === 4 ? 'type' : 'boolean',
            onClick: () => emit('update:value', harness.nextValue),
          },
          'select',
        ),
  }),
}));
let app: App;
let host: HTMLDivElement;
const values = ref<DataforgeEtlApi.ParamItem[]>([]);
beforeEach(() => {
  values.value = [
    {
      name: 'amount',
      type: 'INTEGER',
      min: '0',
      max: '10',
      defaultValue: 'false',
    },
  ];
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () =>
      h(ParameterEditor, {
        modelValue: values.value,
        'onUpdate:modelValue': (value) => {
          values.value = value;
        },
      }),
  });
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});
async function choose(kind: string, value: unknown) {
  harness.nextValue = value;
  host.querySelector<HTMLButtonElement>(`[data-kind="${kind}"]`)!.click();
  await nextTick();
}
describe('参数编辑器选择值边界', () => {
  it('只接受四种类型，忽略number和array且切换时清除不适用约束', async () => {
    await choose('type', 1);
    await choose('type', ['STRING']);
    await choose('type', 'SCRIPT');
    expect(values.value[0]).toMatchObject({
      type: 'INTEGER',
      min: '0',
      max: '10',
    });
    await choose('type', 'BOOLEAN');
    expect(values.value[0]).toMatchObject({
      type: 'BOOLEAN',
      min: undefined,
      max: undefined,
      defaultValue: 'false',
    });
  });
  it('布尔默认值仅接收字面字符串，不把number/array隐式转换', async () => {
    await choose('type', 'BOOLEAN');
    await choose('boolean', 1);
    await choose('boolean', ['true']);
    await choose('boolean', 'other');
    expect(values.value[0]?.defaultValue).toBe('false');
    await choose('boolean', 'true');
    expect(values.value[0]?.defaultValue).toBe('true');
  });
});
