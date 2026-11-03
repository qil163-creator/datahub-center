import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ManagedHttpEditor from './managed-http-editor.vue';

vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number], disabled: Boolean, precision: Number },
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
              props.precision === 0
                ? Number((event.target as HTMLInputElement).value)
                : (event.target as HTMLInputElement).value,
            ),
        }),
  });
  return {
    Input: Field,
    InputNumber: Field,
    TextArea: Field,
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('div', props.message),
    }),
  };
});
vi.mock('./reference-request-editor.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ validate: () => true });
      return () => h('div', '接口参数');
    },
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
function mount(config: Record<string, any>, disabled = false) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({
    render: () =>
      h(ManagedHttpEditor, {
        ref: editor,
        config: state.config,
        disabled: state.disabled,
      }),
  });
  app.mount(host);
  return { state, editor };
}
async function edit(label: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(
    `[aria-label="${label}"]`,
  )!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await nextTick();
}
describe('受管API执行配置', () => {
  it('要求执行资源和有效预算，保留独立接口配置及未知历史属性', async () => {
    const { state, editor } = mount({
      apiConfig: { requestUrl: 'https://fixture.test/rows' },
      vendor: 7,
    });
    expect(editor.value!.validate()).toBe(false);
    await edit('接口执行资源', 'api.resource');
    await edit('接口最大页数', '0');
    expect(editor.value!.validate()).toBe(false);
    await edit('接口最大页数', '20');
    expect(editor.value!.validate()).toBe(true);
    await edit('接口执行资源', '_api.resource');
    expect(editor.value!.validate()).toBe(false);
    await edit('接口执行资源', 'api.resource');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toEqual({
      apiConfig: { requestUrl: 'https://fixture.test/rows' },
      vendor: 7,
      resourceBindingId: 'api.resource',
      maxPages: 20,
    });
  });
  it('只读事件不写入草稿，认证请求头不误当普通参数保存', async () => {
    const { state, editor } = mount(
      {
        resourceBindingId: 'api-resource',
        apiConfig: {
          requestHeader: [{ name: 'Authorization', value: 'test-only' }],
        },
      },
      true,
    );
    await edit('接口执行资源', 'ignored');
    expect(state.config.resourceBindingId).toBe('api-resource');
    expect(editor.value!.validate()).toBe(false);
  });
  it('秘密请求头只保存引用，错误JSON不把半成品写入配置', async () => {
    const { state, editor } = mount({
      resourceBindingId: 'api-resource',
      apiConfig: {},
    });
    await edit('接口秘密请求头引用', '{');
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.headerSecretRefs).toBeUndefined();
    await edit('接口秘密请求头引用', '{"Authorization":"api_access"}');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.headerSecretRefs).toEqual({
      Authorization: 'api_access',
    });
  });
  it('引用合计不超过32个且禁止协议保留头，认证头仍可使用引用', async () => {
    const { state, editor } = mount({ resourceBindingId: 'api.resource', apiConfig: {} });
    const secrets = Object.fromEntries(Array.from({ length: 16 }, (_, index) => [`X-Secret${index}`, `secret${index}`]));
    const contexts = Object.fromEntries(Array.from({ length: 16 }, (_, index) => [`X-Context${index}`, { tokenKey: `token${index}` }]));
    await edit('接口秘密请求头引用', JSON.stringify(secrets));
    await edit('接口认证变量引用', JSON.stringify(contexts));
    expect(editor.value!.validate()).toBe(true);
    await edit('接口认证变量引用', JSON.stringify({ ...contexts, 'X-Overflow': { tokenKey: 'extra' } }));
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('合计最多32个');
    expect(state.config.headerContextRefs).toEqual(contexts);
    await edit('接口秘密请求头引用', '{}');
    await edit('接口认证变量引用', '{}');
    for (const label of ['接口秘密请求头引用', '接口认证变量引用']) {
      for (const name of ['Host', 'cOnNection', 'Content-Length', 'Transfer-Encoding', 'Upgrade']) {
        const value = label === '接口秘密请求头引用' ? 'api_access' : { tokenKey: 'accessToken' };
        await edit(label, JSON.stringify({ [name]: value }));
        expect(editor.value!.validate()).toBe(false);
        await nextTick();
        expect(host.textContent).toContain('协议保留请求头');
        await edit(label, '{}');
      }
    }
    await edit('接口认证变量引用', '{"Authorization":{"tokenKey":"accessToken","prefix":"Bearer "}}');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.headerContextRefs).toEqual({ Authorization: { tokenKey: 'accessToken', prefix: 'Bearer ' } });
  });

  it('认证变量仅保存名称与方案，不覆盖同名秘密引用或接受换行前缀', async () => {
    const { state, editor } = mount({
      resourceBindingId: 'api-resource',
      apiConfig: {},
    });
    await edit(
      '接口认证变量引用',
      '{"Authorization":{"tokenKey":"accessToken","prefix":"Bearer "}}',
    );
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.headerContextRefs).toEqual({
      Authorization: { tokenKey: 'accessToken', prefix: 'Bearer ' },
    });
    await edit('接口秘密请求头引用', '{"authorization":"api_access"}');
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.headerSecretRefs).toBeUndefined();
    await edit('接口秘密请求头引用', '{}');
    await edit(
      '接口认证变量引用',
      String.raw`{"Authorization":{"tokenKey":"accessToken","prefix":"Bearer x\r\n"}}`,
    );
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.headerContextRefs.Authorization.prefix).toBe('Bearer ');
  });
});
