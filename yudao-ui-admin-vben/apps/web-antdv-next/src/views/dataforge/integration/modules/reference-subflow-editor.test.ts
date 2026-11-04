import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl/subflow';

import ReferenceSubflowEditor from './reference-subflow-editor.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken: string },
  user: {} as { userInfo: { userId: string } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('#/api/dataforge/etl/subflow', () => ({
  getSubflowPipelinePage: vi.fn(),
  getSubflowVersionPage: vi.fn(),
  getSubflowContract: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Select: defineComponent({
    props: {
      value: { type: String, default: '' },
      options: { type: Array, default: () => [] },
      loading: Boolean,
      disabled: Boolean,
    },
    emits: ['update:value', 'search', 'focus'],
    setup: (props, { emit }) => () =>
      h('input', {
        value: props.value,
        disabled: props.disabled,
        'data-options': JSON.stringify(props.options),
        onInput: (event: Event) =>
          emit('update:value', (event.target as HTMLInputElement).value),
        onFocus: () => emit('focus'),
      }),
  }),
  Alert: defineComponent({
    props: { message: { type: String, default: '' } },
    setup: (props) => () => h('div', { role: 'alert' }, props.message),
  }),
  Button: defineComponent({
    props: { disabled: Boolean },
    setup: (props, { slots }) => () =>
      h('button', { disabled: props.disabled }, slots.default?.()),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'session-a', refreshToken: 'refresh-a' });
  harness.user = reactive({ userInfo: { userId: '9007199254740993' } });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
function mount(config: Record<string, any>, disabled = false) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  app = createApp({
    render: () => h(ReferenceSubflowEditor, state, {
      description: () => h('p', '节点描述保留'),
    }),
  });
  app.mount(host);
  return state;
}
async function flush() {
  for (let index = 0; index < 5; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
function select() {
  return host.querySelector<HTMLInputElement>('input')!;
}
async function focus() {
  select().dispatchEvent(new Event('focus'));
  await flush();
}
function options() {
  return JSON.parse(select().dataset.options ?? '[]');
}
describe('子流程主配置', () => {
  it('删除固定版本契约面板，浏览和主下拉选择不改写历史引用或参数', async () => {
    vi.mocked(api.getSubflowPipelinePage).mockResolvedValue({
      list: [{ id: '9007199254740995', name: '公共处理流程' }],
      total: 1,
    });
    const state = mount({
      pipelineId: '9007199254740993',
      versionId: '9007199254740994',
      contentHash: 'retained',
      inputs: [{ name: 'in', fields: [{ name: 'id', type: 'LONG' }] }],
      outputs: [{ name: 'out', fields: [{ name: 'id', type: 'LONG' }] }],
      parameters: { region: 'region-placeholder', vendor: '保留' },
      vendor: { unknown: true },
    });
    const before = JSON.stringify(state.config);
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toContain('固定发布版本与输入输出契约');
    expect(host.textContent).toContain('节点描述保留');
    expect(host.textContent).toContain('子流程用于调用已有流程');
    expect(api.getSubflowPipelinePage).not.toHaveBeenCalled();
    await focus();
    expect(options()).toEqual([
      { value: '9007199254740995', label: '公共处理流程' },
    ]);
    select().value = '9007199254740995';
    select().dispatchEvent(new Event('input'));
    await flush();
    expect(select().value).toBe('9007199254740995');
    expect(JSON.stringify(state.config)).toBe(before);
    expect(api.getSubflowVersionPage).not.toHaveBeenCalled();
    expect(api.getSubflowContract).not.toHaveBeenCalled();
  });
  it('流程列表的迟到响应不能覆盖后发请求', async () => {
    type Result = Awaited<ReturnType<typeof api.getSubflowPipelinePage>>;
    let finishFirst!: (result: Result) => void;
    let finishSecond!: (result: Result) => void;
    vi.mocked(api.getSubflowPipelinePage)
      .mockReturnValueOnce(new Promise((resolve) => { finishFirst = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { finishSecond = resolve; }));
    mount({});
    await focus();
    await focus();
    finishSecond({ list: [{ id: '2', name: '新列表' }], total: 1 });
    await flush();
    finishFirst({ list: [{ id: '1', name: '旧列表' }], total: 1 });
    await flush();
    expect(options()).toEqual([{ value: '2', label: '新列表' }]);
  });
  it('会话切换和卸载后丢弃列表响应，不重写当前配置', async () => {
    type Result = Awaited<ReturnType<typeof api.getSubflowPipelinePage>>;
    let finish!: (result: Result) => void;
    vi.mocked(api.getSubflowPipelinePage).mockImplementation(
      () => new Promise((resolve) => { finish = resolve; }),
    );
    const state = mount({ versionId: '9007199254740994', parameters: { keep: 'value' } });
    const before = JSON.stringify(state.config);
    await focus();
    harness.access.accessToken = 'session-b';
    finish({ list: [{ id: '1', name: '旧会话列表' }], total: 1 });
    await flush();
    expect(options()).toEqual([]);
    await focus();
    app!.unmount();
    app = undefined;
    finish({ list: [{ id: '2', name: '卸载后列表' }], total: 1 });
    await flush();
    expect(JSON.stringify(state.config)).toBe(before);
    expect(host.textContent).toBe('');
  });
  it('主流程列表加载失败仍显示提示，既有引用保持不变', async () => {
    vi.mocked(api.getSubflowPipelinePage).mockRejectedValue(new Error('unavailable'));
    const state = mount({ versionId: '9007199254740994', parameters: { keep: 'value' } });
    const before = JSON.stringify(state.config);
    await focus();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('子流程列表加载失败');
    expect(JSON.stringify(state.config)).toBe(before);
  });
});
