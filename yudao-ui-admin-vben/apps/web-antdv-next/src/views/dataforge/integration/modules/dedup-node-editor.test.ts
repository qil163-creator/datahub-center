import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import NodeConfigurationEditor from './node-configuration-editor.vue';

vi.mock('../../etl/pipeline/node-editors/registry', () => ({ getNodeEditor: () => undefined }));
vi.mock('./connector-node-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./database-node-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./managed-crypto-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./managed-http-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./native-script-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-node-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-transform-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number, Array], disabled: Boolean, mode: String },
    emits: ['update:value'],
    setup: (props, { emit }) => () => h('input', { disabled: props.disabled, value: Array.isArray(props.value) ? JSON.stringify(props.value) : props.value,
      onInput: (event: Event) => { const value = (event.target as HTMLInputElement).value; emit('update:value', props.mode === 'tags' ? JSON.parse(value) : value); } }),
  });
  const Container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    AutoComplete: Field, Select: Field, Input: Object.assign(Field, { TextArea: Field }), InputNumber: Field, TextArea: Field,
    ConfigProvider: Container, Dropdown: Container, Menu: Container, MenuItem: Container,
    Button: defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    Checkbox: defineComponent({ props: { checked: Boolean, disabled: Boolean }, emits: ['update:checked'], setup: (props, { emit }) => () => h('input', {
      type: 'checkbox', checked: props.checked, disabled: props.disabled, onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked),
    }) }),
    Alert: defineComponent({ props: { message: String }, setup: (props) => () => h('div', props.message) }), Modal: { confirm: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });
function mount(config: Record<string, any>, disabled = false) {
  host = document.createElement('div'); document.body.append(host);
  const state = reactive({ config, disabled }); const editor = ref<{ validate: () => boolean }>();
  app = createApp({ render: () => h(NodeConfigurationEditor, { ref: editor, componentCode: 'dedup-records', toolCode: 'data-clean', config: state.config, disabled: state.disabled, engine: 'SPARK', mode: 'OFFLINE' }) });
  app.mount(host); return { state, editor };
}
async function edit(label: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  input.value = value; input.dispatchEvent(new Event('input')); await nextTick();
}
async function orderById() {
  [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '增加排序字段')!.click(); await nextTick();
  await edit('第1个排序字段', 'id'); await edit('唯一性字段', '["id"]');
}

describe('去重组件的真实配置与原生顺序合同', () => {
  it('按键保留首条必须显式排序，完整保存键、大小写策略与唯一性字段', async () => {
    const { state, editor } = mount({ keyFields: ['group'], ignoreCase: true, vendor: 'keep' });
    await nextTick(); expect(editor.value!.validate()).toBe(false);
    expect(state.config.orderBy).toBeUndefined(); await orderById();
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toEqual({ enableDeduplication: true, deduplicationStrategy: 'KEY_FIELDS', keyFields: ['group'], ignoreCase: true, vendor: 'keep', orderBy: [{ field: 'id', order: 'ASC' }], uniqueFields: ['id'] });
  });
  it('整行去重无需键或排序，忽略大小写时必须明确保留哪一条原始行', async () => {
    const { state, editor } = mount({ deduplicationStrategy: 'ALL_FIELDS', keyFields: [], ignoreCase: false });
    await nextTick(); expect(state.config.deduplicationStrategy).toBe('ALL_FIELDS');
    expect(editor.value!.validate()).toBe(true); expect(host.textContent).not.toContain('增加排序字段');
    const checkbox = host.querySelector<HTMLInputElement>('input[type="checkbox"]')!; checkbox.checked = true; checkbox.dispatchEvent(new Event('change')); await nextTick();
    expect(editor.value!.validate()).toBe(false); await orderById(); expect(editor.value!.validate()).toBe(true);
    await edit('去重策略', 'KEY_FIELDS'); expect(editor.value!.validate()).toBe(false);
    await edit('去重字段', '["group"]'); expect(editor.value!.validate()).toBe(true);
    await edit('去重策略', 'ALL_FIELDS'); expect(state.config.keyFields).toEqual(['group']);
  });
  it('只读配置不被默认策略或伪造输入事件覆盖', async () => {
    const { state } = mount({ deduplicationStrategy: 'ALL_FIELDS', keyFields: ['group'], ignoreCase: false, vendor: 9 }, true);
    const before = JSON.stringify(state.config); await edit('去重策略', 'KEY_FIELDS');
    const checkbox = host.querySelector<HTMLInputElement>('input[type="checkbox"]')!; checkbox.checked = true; checkbox.dispatchEvent(new Event('change')); await nextTick();
    expect(JSON.stringify(state.config)).toBe(before);
  });
});
