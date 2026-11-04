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
vi.mock('./reference-flow-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-field-merge-dialog.vue', () => ({ default: { render: () => null } }));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number, Array], disabled: Boolean, mode: String },
    emits: ['update:value'],
    setup: (props, { emit }) => () => h('input', { disabled: props.disabled, value: Array.isArray(props.value) ? JSON.stringify(props.value) : props.value,
      onInput: (event: Event) => {
        const value = (event.target as HTMLInputElement).value;
        let nextValue: unknown = value;
        if (props.mode === 'tags') nextValue = JSON.parse(value);
        else if (typeof props.value === 'number') nextValue = Number(value);
        emit('update:value', nextValue);
      } }),
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
function mount(config: Record<string, any>, disabled = false, componentCode = 'dedup-records', toolCode = 'data-clean') {
  host = document.createElement('div'); document.body.append(host);
  const state = reactive({ config, disabled }); const editor = ref<{ validate: () => boolean }>();
  app = createApp({ render: () => h(NodeConfigurationEditor, { ref: editor, componentCode, toolCode, inputFields: [{ name: 'group', dataType: 'STRING' }, { name: 'id', dataType: 'BIGINT' }, { name: 'label', dataType: 'STRING' }], config: state.config, disabled: state.disabled, engine: 'SPARK', mode: 'OFFLINE' }) });
  app.mount(host); return { state, editor };
}
async function edit(label: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  input.value = value; input.dispatchEvent(new Event('input')); await nextTick();
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === label || item.getAttribute('aria-label') === label)!;
  expect(button).toBeTruthy();
  button.click();
  await nextTick();
}

describe('去重组件主要字段草稿', () => {
  it('删除策略和排序面板后直接保存，保留既有顺序与唯一性声明', async () => {
    const orderBy = [{ field: 'id', order: 'DESC' }];
    const { state, editor } = mount({ keyFields: ['group'], ignoreCase: true, vendor: 'keep', orderBy, uniqueFields: ['id'] });
    await nextTick();
    expect(host.querySelector('[aria-label="去重策略"]')).toBeNull();
    expect(host.querySelector('[aria-label="唯一性字段"]')).toBeNull();
    expect(host.textContent).not.toContain('增加排序字段');
    expect(editor.value!.validate()).toBe(true);
    await edit('第1行去重字段', 'label');
    await edit('label忽略大小写', '0');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toMatchObject({
      enableDeduplication: true, deduplicationStrategy: 'KEY_FIELDS', keyFields: ['label'],
      ignoreCase: true, ignoreCaseFields: [], vendor: 'keep', orderBy, uniqueFields: ['id'],
    });
  });
  it('保留主表新增与重复字段校验，不要求输入已删除的排序参数', async () => {
    const { state, editor } = mount({ keyFields: ['group'] });
    await click('新增');
    expect(state.config.keyFields).toEqual(['group', 'id']);
    await edit('第2行去重字段', 'group');
    expect(editor.value!.validate()).toBe(false);
    await edit('第2行去重字段', 'id');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.orderBy).toBeUndefined();
    expect(state.config.uniqueFields).toBeUndefined();
  });
  it('全字段历史策略不被打开页面或主字段编辑自动改写，只读阻止伪造输入', async () => {
    const { state, editor } = mount({ deduplicationStrategy: 'ALL_FIELDS', keyFields: ['group'], ignoreCase: false, vendor: 9 }, true);
    const before = JSON.stringify(state.config);
    await edit('第1行去重字段', 'id');
    await edit('group忽略大小写', '1');
    await click('新增');
    expect(editor.value!.validate()).toBe(true);
    expect(JSON.stringify(state.config)).toBe(before);
  });
  it.each([
    ['id-generator', 'id-generator', { mode: 'SEQUENCE' }],
    ['column-to-row', 'row-column', { mode: 'COL2ROW' }],
    ['row-to-column', 'row-column', { mode: 'ROW2COL', pivotMappings: [{ key: 'A' }] }],
  ] as const)('%s 不再附加原生顺序编辑器，历史顺序声明保持原值', async (componentCode, toolCode, config) => {
    const historical = { ...config, orderBy: [{ field: 'id', order: 'ASC' }], uniqueFields: ['id'] };
    const { state } = mount(historical, false, componentCode, toolCode);
    await nextTick();
    expect(host.querySelector('[aria-label="唯一性字段"]')).toBeNull();
    expect(host.textContent).not.toContain('增加排序字段');
    expect(state.config).toEqual(historical);
  });
});
