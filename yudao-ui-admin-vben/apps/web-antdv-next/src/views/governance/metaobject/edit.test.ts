/* eslint-disable vue/one-component-per-file -- 业务编辑回归使用轻量表单控件替身。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMetaTableDetail,
  updateMetaTableBiz,
} from '#/api/governance/metaobject';
import { getSimpleUserList } from '#/api/system/user';

import Edit from './edit.vue';

const state = vi.hoisted(() => ({
  route: undefined as undefined | { name: string; params: { id: string } },
}));
vi.mock('vue-router', () => {
  state.route = reactive({
    name: 'GovernanceMetaTableEdit',
    params: { id: '9007199254740993' },
  });
  return { useRoute: () => state.route, useRouter: () => ({ push: vi.fn() }) };
});
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: '' }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('./data', () => ({ displayComment: () => '源端表注释' }));
vi.mock('#/api/governance/metaobject', () => ({
  getMetaTableDetail: vi.fn(),
  updateMetaTableBiz: vi.fn(),
}));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = defineComponent({
    props: {
      value: { type: [String, Number], default: '' },
      disabled: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: slot,
    Spin: slot,
    Input: input,
    TextArea: input,
    Select: input,
    RadioGroup: slot,
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots, attrs }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
    message: { success: vi.fn() },
  };
});
let app: App;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 18; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getMetaTableDetail).mockResolvedValue({
    id: '9007199254740993',
    bizRevision: 7,
    publishStatus: 0,
    tableName: 'SOURCE_TABLE',
    storageType: '业务存储原值',
    engine: '源端物理引擎',
    temporaryFlag: 1,
    businessDefinition: '已保存的业务描述',
    businessLeaderName: '源端负责人',
  });
  vi.mocked(getSimpleUserList).mockResolvedValue([]);
  vi.mocked(updateMetaTableBiz).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Edit);
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('整页库表业务属性编辑', () => {
  it('独立维护存储类型并保留无损ID、临时表与业务描述，不改写采集引擎', async () => {
    await flush();
    const storage = host.querySelector<HTMLInputElement>(
      'input[placeholder="请输入存储类型"]',
    )!;
    expect(storage.disabled).toBe(false);
    expect(storage.maxLength).toBe(128);
    expect(storage.value).toBe('业务存储原值');
    storage.value = '列式压缩存储';
    storage.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    const save = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '暂存',
    )!;
    save.click();
    await flush();
    expect(updateMetaTableBiz).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        bizRevision: 7,
        storageType: '列式压缩存储',
        temporaryFlag: 1,
        businessDefinition: '已保存的业务描述',
      }),
    );
    expect(vi.mocked(updateMetaTableBiz).mock.calls[0]?.[0]).not.toHaveProperty(
      'engine',
    );
    expect(
      vi.mocked(updateMetaTableBiz).mock.calls[0]?.[0].businessLeaderUserId,
    ).toBeUndefined();
    expect(save.disabled).toBe(false);
  });
});
