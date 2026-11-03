import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import NativeOrderEditor from './native-order-editor.vue';

vi.mock('antdv-next', () => {
  const Field = defineComponent({ props: { value: [String, Array], disabled: Boolean, mode: String }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: Array.isArray(props.value) ? JSON.stringify(props.value) : props.value, disabled: props.disabled, onInput: (event: Event) => { const value = (event.target as HTMLInputElement).value; emit('update:value', props.mode === 'tags' ? JSON.parse(value) : value); } }) });
  return { AutoComplete: Field, Select: Field, Button: defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }), Alert: defineComponent({ props: { message: String }, setup: (props) => () => h('div', props.message) }) };
});
let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });
function mount(config: Record<string, any>, disabled = false) {
  host = document.createElement('div'); document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({ render: () => h(NativeOrderEditor, { ref: editor, config: state.config, disabled: state.disabled, inputFields: [{ name: 'id' }] }) });
  app.mount(host); return { state, editor };
}
async function edit(label: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!; input.value = value; input.dispatchEvent(new Event('input')); await nextTick();
}
describe('确定顺序与唯一性配置', () => {
  it('不推断默认排序，只有显式排序覆盖唯一性字段后才能保存', async () => {
    const { state, editor } = mount({ start: '9007199254740993', step: '1', vendor: 9 });
    expect(state.config.orderBy).toBeUndefined(); expect(state.config.uniqueFields).toBeUndefined();
    expect(editor.value!.validate()).toBe(false);
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '增加排序字段')!.click(); await nextTick();
    await edit('第1个排序字段', 'time'); await edit('唯一性字段', '["id"]');
    expect(editor.value!.validate()).toBe(false);
    await edit('第1个排序字段', 'id'); await edit('第1个排序方向', 'DESC');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toEqual({ start: '9007199254740993', step: '1', vendor: 9, orderBy: [{ field: 'id', order: 'DESC' }], uniqueFields: ['id'] });
  });
  it('保留排序优先级和未知属性，只读事件不能改顺序或字段', async () => {
    const { state, editor } = mount({ orderBy: [{ field: 'time', order: 'ASC', vendor: 1 }, { field: 'id', order: 'DESC' }], uniqueFields: ['id'] });
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '下移')!.click(); await nextTick();
    expect(state.config.orderBy.map((row: { field: string }) => row.field)).toEqual(['id', 'time']);
    expect(state.config.orderBy[1].vendor).toBe(1); expect(editor.value!.validate()).toBe(true);
    state.disabled = true; await nextTick();
    await edit('第1个排序字段', 'ignored'); expect(state.config.orderBy[0].field).toBe('id');
  });
});
