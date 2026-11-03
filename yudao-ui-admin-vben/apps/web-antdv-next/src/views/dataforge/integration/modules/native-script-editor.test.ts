/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身只模拟输入与草稿事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import NativeScriptEditor from './native-script-editor.vue';

vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    props: { modelValue: String, readonly: Boolean },
    emits: ['update:modelValue'],
    setup: (props) => () =>
      h('pre', { 'aria-label': 'Java代码编辑器' }, props.modelValue),
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
  const NumberInput = defineComponent({
    props: { value: Number, disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit(
              'update:value',
              Number((event.target as HTMLInputElement).value),
            ),
        }),
  });
  return {
    Input,
    InputNumber: NumberInput,
    Select: Input,
    AutoComplete: Input,
    TextArea: Input,
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
  const element = host.querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
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
  it('不自动假设运行状态和批次预算，用户明确填写后才通过', async () => {
    const editor = mount({ code: 'existing code', unknown: '保留' });
    expect(editor.state.config).toEqual({
      code: 'existing code',
      unknown: '保留',
    });
    expect(editor.valid()).toBe(false);
    await input('脚本资源编号', 'script.resource');
    await input('脚本运行状态', 'BATCH');
    await input('脚本批次策略', 'PARTITION');
    await input('脚本最大批次行数', '1000');
    expect(editor.valid()).toBe(true);
    expect(editor.state.config.unknown).toBe('保留');
    editor.state.mode = 'REALTIME';
    editor.state.engine = 'FLINK';
    await nextTick();
    expect(editor.state.config.batchPolicy).toBe('PARTITION');
    expect(editor.valid()).toBe(false);
    await input('脚本批次策略', 'COUNT_OR_TIME');
    await input('脚本最长等待毫秒', '100');
    expect(editor.valid()).toBe(true);
  });
  it('旧参数只显式清理，认证令牌不冒充输出字段且只读不修改草稿', async () => {
    const editor = mount(
      {
        code: 'token code',
        resourceBindingId: 'token.resource',
        workerState: 'BATCH',
        batchPolicy: 'PARTITION',
        maximumRows: 1,
        batchSize: 1000,
        tokenKey: 'api.token',
        classPath: 'historical.jar',
        outputFields: [{ columnName: 'token', columnType: 'STRING' }],
      },
      'custom-token',
    );
    expect(editor.valid()).toBe(false);
    expect(editor.state.config.batchSize).toBe(1000);
    await click('清除历史批次行数');
    await click('清除历史输出字段');
    expect(editor.valid()).toBe(true);
    expect(editor.state.config.classPath).toBe('historical.jar');
    editor.state.disabled = true;
    await nextTick();
    await input('原生脚本代码', 'changed');
    expect(editor.state.config.code).toBe('token code');
  });
  it('实时认证保持启动初始化，旧行窗口必须显式转换且不改认证引用', async () => {
    const editor = mount({
      code: 'return opaqueToken;', resourceBindingId: 'token.resource',
      workerState: 'BATCH', batchPolicy: 'COUNT_OR_TIME', maximumRows: 1,
      flushMillis: 100, tokenKey: 'api.token', unknown: '保留',
    }, 'custom-token');
    editor.state.mode = 'REALTIME';
    editor.state.engine = 'FLINK';
    await nextTick();
    expect(host.querySelector('[aria-label="脚本最长等待毫秒"]')).toBeNull();
    expect(editor.valid()).toBe(false);
    expect(editor.state.config.batchPolicy).toBe('COUNT_OR_TIME');
    expect(editor.state.config.flushMillis).toBe(100);
    await input('脚本批次策略', 'PARTITION');
    await click('移除历史等待时间');
    expect(editor.valid()).toBe(true);
    expect(editor.state.config.tokenKey).toBe('api.token');
    expect(editor.state.config.unknown).toBe('保留');
  });
});
