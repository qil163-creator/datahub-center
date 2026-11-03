/* eslint-disable vue/one-component-per-file -- 隔离表单渲染，验证查询值回显和事件契约。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import NativeSearch from './native-search.vue';

interface FormOptions {
  handleSubmit: (values: Record<string, unknown>) => void;
  handleValuesChange: (values: Record<string, unknown>) => void;
  submitOnEnter?: boolean;
}
const state = vi.hoisted(() => ({
  options: undefined as FormOptions | undefined,
  values: {} as Record<string, unknown>,
  reset: vi.fn(),
  setValues: vi.fn(),
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: FormOptions) => {
    state.options = options;
    state.setValues.mockImplementation(async (values: Record<string, unknown>) => {
      Object.assign(state.values, values);
    });
    state.reset.mockImplementation(async () => {
      state.values.name = undefined;
      state.values.status = undefined;
    });
    return [defineComponent({
      setup: (_, { slots }) => () => h('form', {
        onKeydown: (event: KeyboardEvent) => {
          if (event.key === 'Enter' && options.submitOnEnter) {
            event.preventDefault();
            options.handleSubmit({ ...state.values });
          }
        },
      }, [
        h('input', {
          value: state.values.name ?? '',
          onInput: (event: Event) => {
            state.values.name = (event.target as HTMLInputElement).value;
            options.handleValuesChange({ ...state.values });
          },
        }),
        slots['reset-before']?.(), slots['submit-before']?.(),
      ]),
    }), {
      reset: state.reset, setValues: state.setValues, setState: vi.fn(),
      submit: async () => options.handleSubmit({ ...state.values }),
    }];
  },
}));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    setup: (_, { attrs, slots }) => () => h('button', { ...attrs, type: 'button' }, slots.default?.()),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
const submit = vi.fn();
const filters = reactive<{ name?: string; status?: number }>({});
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(NativeSearch, {
    schema: [
      { fieldName: 'name', component: 'Input', label: '名称' },
      { fieldName: 'status', component: 'Select', label: '状态' },
    ],
    values: filters,
    onValues: (values) => Object.assign(filters, values),
    onSubmit: () => submit({ ...filters }),
    onReset: () => Object.assign(filters, { name: undefined, status: undefined }),
  }) });
  app.mount(host);
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  state.values = reactive({});
  Object.assign(filters, { name: '原条件', status: 0 });
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });
it('回显已有查询及状态0，并同步外部清空到实际表单', async () => {
  await mount();
  expect(host.querySelector('input')?.value).toBe('原条件');
  expect(state.values.status).toBe(0);
  Object.assign(filters, { name: undefined, status: undefined });
  await nextTick();
  expect(host.querySelector('input')?.value).toBe('');
  expect(state.setValues).toHaveBeenLastCalledWith({ name: undefined, status: undefined });
});
it('输入后回车提交当前条件，重置同时清表单和外部条件', async () => {
  await mount();
  const input = host.querySelector('input')!;
  input.value = '新条件';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  expect(submit).toHaveBeenCalledWith({ name: '新条件', status: 0 });
  [...host.querySelectorAll('button')].find((button) => button.textContent?.includes('重置'))!.click();
  await nextTick(); await nextTick();
  expect(state.reset).toHaveBeenCalledTimes(1);
  expect(input.value).toBe('');
  expect(filters).toEqual({ name: undefined, status: undefined });
});
