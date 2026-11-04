import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ManagedHttpEditor from './managed-http-editor.vue';

vi.mock('antdv-next', () => {
  return {
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
describe('接口API输入主配置', () => {
  it('移除高级面板后无需隐藏资源字段即可保存，保留独立接口配置及未知历史属性', () => {
    const { state, editor } = mount({
      apiConfig: { requestUrl: 'https://fixture.test/rows' },
      vendor: 7,
    });
    expect(host.textContent).toContain('接口参数');
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toContain('高级执行配置');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toEqual({
      apiConfig: { requestUrl: 'https://fixture.test/rows' },
      vendor: 7,
    });
  });
  it('既有执行资源和预算仍校验，错误显示在主配置中', async () => {
    const { state, editor } = mount({
      resourceBindingId: 'api.resource',
      maxPages: 0,
      apiConfig: {},
    });
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('读取预算');
    state.config.maxPages = 20;
    expect(editor.value!.validate()).toBe(true);
    state.config.resourceBindingId = '_api.resource';
    expect(editor.value!.validate()).toBe(false);
    state.config.resourceBindingId = 'api.resource';
    expect(editor.value!.validate()).toBe(true);
    expect(state.config).toEqual({
      apiConfig: {},
      resourceBindingId: 'api.resource',
      maxPages: 20,
    });
  });
  it('只读校验不写入草稿，认证请求头不误当普通参数保存', () => {
    const { state, editor } = mount(
      {
        resourceBindingId: 'api-resource',
        apiConfig: {
          requestHeader: [{ name: 'Authorization', value: 'test-only' }],
        },
      },
      true,
    );
    expect(state.config.resourceBindingId).toBe('api-resource');
    expect(editor.value!.validate()).toBe(false);
  });
  it('保留既有请求头引用并拒绝错误JSON', () => {
    const { state, editor } = mount({
      resourceBindingId: 'api-resource',
      apiConfig: {},
      headerSecretRefs: '{',
    });
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.headerSecretRefs).toBe('{');
    state.config.headerSecretRefs = '{"Authorization":"api_access"}';
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.headerSecretRefs).toEqual({
      Authorization: 'api_access',
    });
  });
  it('引用合计不超过32个且禁止协议保留头，认证头仍可使用引用', async () => {
    const { state, editor } = mount({ resourceBindingId: 'api.resource', apiConfig: {} });
    const secrets = Object.fromEntries(Array.from({ length: 16 }, (_, index) => [`X-Secret${index}`, `secret${index}`]));
    const contexts = Object.fromEntries(Array.from({ length: 16 }, (_, index) => [`X-Context${index}`, { tokenKey: `token${index}` }]));
    state.config.headerSecretRefs = secrets;
    state.config.headerContextRefs = contexts;
    expect(editor.value!.validate()).toBe(true);
    state.config.headerContextRefs = { ...contexts, 'X-Overflow': { tokenKey: 'extra' } };
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('合计最多32个');
    expect(state.config.headerContextRefs).toHaveProperty('X-Overflow');
    state.config.headerSecretRefs = {};
    state.config.headerContextRefs = {};
    for (const key of ['headerSecretRefs', 'headerContextRefs']) {
      for (const name of ['Host', 'cOnNection', 'Content-Length', 'Transfer-Encoding', 'Upgrade']) {
        const value = key === 'headerSecretRefs' ? 'api_access' : { tokenKey: 'accessToken' };
        state.config[key] = { [name]: value };
        expect(editor.value!.validate()).toBe(false);
        await nextTick();
        expect(host.textContent).toContain('协议保留请求头');
        state.config[key] = {};
      }
    }
    state.config.headerContextRefs = { Authorization: { tokenKey: 'accessToken', prefix: 'Bearer ' } };
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.headerContextRefs).toEqual({ Authorization: { tokenKey: 'accessToken', prefix: 'Bearer ' } });
  });

  it('既有认证变量不允许同名秘密引用或换行前缀，只读校验保持原格式', () => {
    const { state, editor } = mount({
      resourceBindingId: 'api-resource',
      apiConfig: {},
      headerContextRefs: '{"Authorization":{"tokenKey":"accessToken","prefix":"Bearer "}}',
    },
      true,
    );
    const before = JSON.stringify(state.config);
    expect(editor.value!.validate()).toBe(true);
    expect(JSON.stringify(state.config)).toBe(before);
    state.config.headerSecretRefs = { authorization: 'api_access' };
    expect(editor.value!.validate()).toBe(false);
    state.config.headerSecretRefs = {};
    state.config.headerContextRefs = String.raw`{"Authorization":{"tokenKey":"accessToken","prefix":"Bearer x\r\n"}}`;
    expect(editor.value!.validate()).toBe(false);
  });
});
