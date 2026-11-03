/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身只模拟可见草稿交互。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Editor from './native-streaming-window-editor.vue';
import { validateStreamingWindow } from './native-streaming-window-schema';

vi.mock('antdv-next', () => ({
  Button: defineComponent({props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.())}),
  InputNumber: defineComponent({props: { value: Number, disabled: Boolean }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', {value:props.value, disabled:props.disabled, onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))})}),
}));
let app: App | undefined; let host: HTMLDivElement;
afterEach(() => { app?.unmount(); host?.remove(); });
function mount(config: Record<string, any>, disabled = false) {
  host=document.createElement('div'); document.body.append(host);
  const state=reactive({config,disabled}); app=createApp({render:() => h(Editor,state)});app.mount(host);return state;
}
describe('有限实时窗口', () => {
  it('历史草稿不自动配置窗口，明确启用后保存固定处理时间合同', async () => {
    const state=mount({unknown:'保留'});expect(state.config).toEqual({unknown:'保留'});
    expect(validateStreamingWindow(state.config.streamingWindow)).toHaveLength(1);
    host.querySelector('button')!.click();await nextTick();
    expect(validateStreamingWindow(state.config.streamingWindow)).toEqual([]);
    expect(state.config.streamingWindow).toMatchObject({timeMode:'PROCESSING_TIME',emit:'ON_CLOSE',latePolicy:'PROCESS_ON_ARRIVAL'});
    const input=host.querySelector<HTMLInputElement>('[aria-label="实时窗口毫秒"]')!;
    input.value='999';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();
    expect(validateStreamingWindow(state.config.streamingWindow)[0]).toContain('1000');
    expect(state.config.unknown).toBe('保留');
  });
  it('只读不启用且未知合同不冒充支持', async () => {
    const state=mount({},true);host.querySelector('button')!.click();await nextTick();expect(state.config).toEqual({});
    expect(validateStreamingWindow({version:2})).toHaveLength(1);
  });
});
