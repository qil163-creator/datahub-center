/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身只模拟输入与草稿事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import NativeScriptEditor from './native-script-editor.vue';

vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    props: { modelValue: String, readonly: Boolean },
    emits: ['update:modelValue'],
    setup: (props, { emit }) => () =>
      h('textarea', {
        'aria-label': 'Java代码编辑器',
        value: props.modelValue,
        readonly: props.readonly,
        onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
      }),
  }),
}));

vi.mock('antdv-next', () => {
  const Input = defineComponent({
    props: { value: [String, Number], disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Input,
    Select: Input,
    AutoComplete: Input,
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(config: Record<string, any>, toolCode = 'java-script') {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({
    config,
    mode: 'OFFLINE',
    engine: 'SPARK',
    disabled: false,
  });
  const editor = ref<{ validate: () => boolean }>();
  const requested = vi.fn();
  app = createApp({
    render: () => h(NativeScriptEditor, { ...state, toolCode, ref: editor, onRequestCheck: requested }),
  });
  app.mount(host);
  return { state, requested, valid: () => editor.value!.validate() };
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    `[aria-label="${label}"]`,
  )!;
  expect(element).toBeTruthy();
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === label,
  )!;
  expect(button).toBeTruthy();
  button.click();
  await nextTick();
}
describe('原生脚本编辑器', () => {
  it('蓝色编译入口只请求父级完整图检查，只读时不提交', async () => {
    const editor=mount({code:'class Draft {}'});
    await click('编译');expect(editor.requested).toHaveBeenCalledTimes(1);
    editor.state.disabled=true;await nextTick();
    await click('编译');expect(editor.requested).toHaveBeenCalledTimes(1);
  });
  it.each(['java-script', 'custom-token'])(
    '%s 删除执行资源与批次面板后可保存主要配置，不注入运行参数',
    async (toolCode) => {
      const config = { code: 'existing code', unknown: '保留', ...(toolCode === 'custom-token' ? { tokenKey: 'api.token' } : {}) };
      const original = structuredClone(config);
      const editor = mount(config, toolCode);
      await nextTick();
      expect(host.textContent).not.toContain('执行资源与批次配置');
      expect(host.querySelector('[aria-label="脚本资源编号"]')).toBeNull();
      expect(host.querySelector('[aria-label="脚本最大批次行数"]')).toBeNull();
      await vi.dynamicImportSettled();
      await nextTick();
      await input('Java代码编辑器', 'updated code');
      await click('编译');
      expect(editor.requested).toHaveBeenCalledOnce();
      expect(editor.valid()).toBe(true);
      expect(editor.state.config).toEqual({ ...original, code: 'updated code' });
      expect(editor.state.config.resourceBindingId).toBeUndefined();
      expect(editor.state.config.workerState).toBeUndefined();
      expect(editor.state.config.maximumRows).toBeUndefined();
    },
  );
  it('保留输出字段及空值契约的编辑校验，不修改历史执行配置', async () => {
    const editor = mount({
      code: 'existing code',
      resourceBindingId: 'script.resource',
      batchPolicy: 'PARTITION',
      maximumRows: 1000,
      batchSize: 200,
      classPath: 'historical.jar',
      outputFields: [{ name: 'old', type: 'STRING', nullable: false, custom: 42 }],
    });
    expect(host.textContent).toContain('输出字段空值契约');
    await input('脚本第1行输出名称', '');
    expect(editor.valid()).toBe(false);
    await input('脚本第1行输出名称', 'renamed');
    await input('脚本第1行输出类型', 'DECIMAL(30,4)');
    expect(editor.valid()).toBe(true);
    expect(editor.state.config.outputFields[0]).toMatchObject({
      name: 'renamed', columnName: 'renamed', type: 'DECIMAL(30,4)', columnType: 'DECIMAL(30,4)', nullable: false, custom: 42,
    });
    expect(editor.state.config).toMatchObject({ resourceBindingId: 'script.resource', batchPolicy: 'PARTITION', maximumRows: 1000, batchSize: 200, classPath: 'historical.jar' });
    editor.state.disabled = true;
    await nextTick();
    await input('脚本第1行输出名称', 'ignored');
    expect(editor.state.config.outputFields[0].name).toBe('renamed');
  });
  it('认证保留历史执行配置，认证变量可编辑且只读代码事件不写草稿', async () => {
    const editor = mount({
      code: 'return opaqueToken;', resourceBindingId: 'token.resource',
      workerState: 'BATCH', batchPolicy: 'COUNT_OR_TIME', maximumRows: 1,
      flushMillis: 100, tokenKey: 'api.token', unknown: '保留',
      outputFields: [{ columnName: 'token', columnType: 'STRING' }],
    }, 'custom-token');
    editor.state.mode = 'REALTIME';
    editor.state.engine = 'FLINK';
    await nextTick();
    expect(editor.valid()).toBe(true);
    await input('脚本认证变量编号', 'updated.token');
    expect(editor.state.config.tokenKey).toBe('updated.token');
    expect(editor.state.config).toMatchObject({ batchPolicy: 'COUNT_OR_TIME', flushMillis: 100, resourceBindingId: 'token.resource', unknown: '保留', outputFields: [{ columnName: 'token', columnType: 'STRING' }] });
    editor.state.disabled = true;
    await nextTick();
    await vi.dynamicImportSettled();
    await nextTick();
    await input('Java代码编辑器', 'ignored');
    expect(editor.state.config.code).toBe('return opaqueToken;');
  });
});
