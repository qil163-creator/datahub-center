/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身验证异步编辑器失败及重试。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('antdv-next', () => ({
  TextArea: defineComponent({
    props: { value: String, readonly: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          value: props.value,
          readonly: props.readonly,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLTextAreaElement).value),
        }),
  }),
  Button: defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.resetModules();
  vi.doUnmock('#/components/monaco/sql-editor.vue');
});
async function flush() {
  await nextTick();
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
async function mount(readonly = false) {
  const { default: Editor } = await import('./table-input-sql-editor.vue');
  const state = reactive({ modelValue: 'select original', readonly });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(Editor, {
        ...state,
        height: '190px',
        'onUpdate:modelValue': (value: string) => {
          state.modelValue = value;
        },
      }),
  });
  app.mount(host);
  return state;
}
function input(value: string) {
  const textarea = host.querySelector('textarea')!;
  textarea.value = value;
  textarea.dispatchEvent(new Event('input'));
}

describe('异步SQL编辑器降级', () => {
  it('等待Monaco时有固定高度的输入区，切换成功不丢失等待期间输入', async () => {
    let resolve!: () => void;
    const ready = new Promise<void>((done) => {
      resolve = done;
    });
    vi.doMock('#/components/monaco/sql-editor.vue', async () => {
      await ready;
      return {
        default: defineComponent({
          props: { modelValue: String },
          setup: (props) => () =>
            h('div', { 'data-monaco': true }, props.modelValue),
        }),
      };
    });
    const state = await mount();
    expect(host.querySelector('textarea')).toBeTruthy();
    expect(
      host.querySelector('.qi-sql-editor')?.getAttribute('style'),
    ).toContain('190px');
    input('select edited_while_loading');
    await nextTick();
    expect(state.modelValue).toBe('select edited_while_loading');
    resolve();
    await flush();
    expect(host.querySelector('[data-monaco]')?.textContent).toBe(
      'select edited_while_loading',
    );
  });
  it('运行初始化失败后不显示提示并保留可编辑的SQL', async () => {
    vi.doMock('#/components/monaco/sql-editor.vue', () => ({
      default: defineComponent({
        props: { modelValue: String },
        setup: () => {
          throw new Error('编辑器初始化失败');
        },
      }),
    }));
    const state = await mount();
    await flush();
    expect(host.textContent).not.toContain('代码编辑器加载失败');
    expect(host.querySelector('button')).toBeNull();
    input('select fallback');
    await nextTick();
    expect(state.modelValue).toBe('select fallback');
    expect(host.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('select fallback');
  });
  it('模块加载失败时无未处理拒绝，且只读模式的降级事件不能写回', async () => {
    vi.doMock('#/components/monaco/sql-editor.vue', () => {
      throw new Error('资源加载失败');
    });
    const state = await mount(true);
    await flush();
    expect(host.textContent).not.toContain('代码编辑器加载失败');
    expect(host.querySelector('textarea')?.readOnly).toBe(true);
    input('changed');
    await nextTick();
    expect(state.modelValue).toBe('select original');
  });
});
