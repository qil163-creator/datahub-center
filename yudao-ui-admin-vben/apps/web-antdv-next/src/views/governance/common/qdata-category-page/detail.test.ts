/* eslint-disable vue/one-component-per-file -- 类目详情测试隔离弹窗与只读字段组件 */
import type { App, PropType } from 'vue';

import type { CategoryAdapter, CategoryRow } from './model';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  ref,
  shallowRef,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Detail from './detail.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | {
        accessCodes: string[];
        accessToken: string;
        loginExpired: boolean;
        refreshToken: string;
      },
  user: undefined as
    | undefined
    | { userInfo: { userId: string }; userRoles: string[] },
  errors: [] as unknown[],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({
  formatDate: (value: unknown) => `时间：${value}`,
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: {
        type: Array as PropType<{ label: string; value?: unknown }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'dl',
        props.fields.map((field) =>
          h('div', [h('dt', field.label), h('dd', String(field.value ?? '-'))]),
        ),
      ),
  }),
}));
vi.mock('antdv-next', () => ({
  Modal: defineComponent({
    props: {
      centered: Boolean,
      open: Boolean,
      title: { type: String, default: '' },
      width: { type: Number, default: 800 },
    },
    setup:
      (props, { slots, emit }) =>
      () =>
        props.open
          ? h(
              'section',
              {
                'data-centered': String(props.centered),
                'data-width': props.width,
              },
              [
                h('h2', props.title),
                slots.default?.(),
                slots.footer?.(),
                h('button', { onClick: () => emit('cancel') }, '关闭弹窗'),
              ],
            )
          : null,
  }),
  Alert: defineComponent({
    props: { message: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('div', { role: 'alert' }, [props.message, slots.action?.()]),
  }),
  Button: defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Spin: defineComponent({
    props: { spinning: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('div', { 'data-loading': String(props.spinning) }, slots.default?.()),
  }),
}));

const id = '9007199254740995';
const parentId = '9007199254740997';
const row: CategoryRow = {
  id,
  parentId,
  key: id,
  name: '当前类目',
  description: '只读说明',
  status: 0,
  sort: 0,
  creatorName: '创建人员',
  createTime: 1_791_939_600_000,
};
const parent: CategoryRow = {
  ...row,
  id: parentId,
  parentId: '0',
  key: parentId,
  name: '真实上级类目',
};
function makeAdapter(
  prefix = 'governance:category-term',
  label = '业务术语类目',
): CategoryAdapter {
  return {
    label,
    nameLabel: `${label}名称`,
    permissionPrefix: prefix,
    get: vi.fn(async () => row),
    list: vi.fn(async () => [parent, row]),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    normalize: (value) => value as CategoryRow,
    toPayload: (value) => value,
  };
}
function deferred() {
  let resolve!: (value: CategoryRow) => void;
  const promise = new Promise<CategoryRow>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 6; index++) await Promise.resolve();
  await nextTick();
}
let app: App | undefined;
let host: HTMLDivElement;
const adapter = shallowRef(makeAdapter());
const instance = ref<InstanceType<typeof Detail>>();
beforeEach(() => {
  harness.access = reactive({
    accessCodes: ['governance:category-term:query'],
    accessToken: 'session-a',
    loginExpired: false,
    refreshToken: '',
  });
  harness.user = reactive({ userInfo: { userId: '9' }, userRoles: ['reader'] });
  harness.errors = [];
  adapter.value = makeAdapter();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(
    defineComponent({
      setup: () => () => h(Detail, { ref: instance, adapter: adapter.value }),
    }),
  );
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('类目只读详情弹窗', () => {
  it.each([
    { prefix: 'governance:category-term', label: '业务术语类目' },
    { prefix: 'asset:category-tag', label: '标签类目' },
    { prefix: 'governance:category-clean-rule', label: '清洗规则类目' },
  ])(
    '$label 按无损编号读取最新详情与真实上级，详情不写入',
    async ({ prefix, label }) => {
      adapter.value = makeAdapter(prefix, label);
      harness.access!.accessCodes = [`${prefix}:query`];
      await nextTick();
      await instance.value!.open(id);
      await nextTick();
      expect(adapter.value.get).toHaveBeenCalledExactlyOnceWith(id);
      expect(adapter.value.list).toHaveBeenCalledExactlyOnceWith();
      for (const text of [
        label,
        id,
        '当前类目',
        '真实上级类目',
        '启用',
        '只读说明',
        '创建人员',
        `时间：${row.createTime}`,
      ])
        expect(host.textContent).toContain(text);
      const fields = [...host.querySelectorAll('dl > div')];
      expect(
        fields
          .find((item) => item.querySelector('dt')?.textContent === '排序')
          ?.querySelector('dd')?.textContent,
      ).toBe('0');
      expect(
        host.querySelector<HTMLElement>('[data-centered]')?.dataset.centered,
      ).toBe('true');
      expect(adapter.value.create).not.toHaveBeenCalled();
      expect(adapter.value.update).not.toHaveBeenCalled();
      expect(adapter.value.remove).not.toHaveBeenCalled();
      expect(harness.errors).toEqual([]);
    },
  );

  it('新对象请求完成前，旧响应不能覆盖数据或关闭loading', async () => {
    const old = deferred();
    const latest = deferred();
    vi.mocked(adapter.value.get)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = instance.value!.open(id);
    const second = instance.value!.open('9007199254740999');
    old.resolve({ ...row, name: '旧详情' });
    await first;
    await nextTick();
    expect(host.textContent).not.toContain('旧详情');
    expect(
      host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading,
    ).toBe('true');
    latest.resolve({ ...row, id: '9007199254740999', name: '最新详情' });
    await second;
    await nextTick();
    expect(host.textContent).toContain('最新详情');
    expect(
      host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading,
    ).toBe('false');
  });

  it.each([
    'close',
    'session',
    'user',
    'permission',
    'adapter',
    'unmount',
  ] as const)('%s 后丢弃未完成的响应', async (change) => {
    const pending = deferred();
    vi.mocked(adapter.value.get).mockReturnValueOnce(pending.promise);
    const loading = instance.value!.open(id);
    if (change === 'close') instance.value!.close();
    if (change === 'session') {
      harness.access!.accessToken = 'session-b';
      harness.access!.accessToken = 'session-a';
    }
    if (change === 'user') harness.user!.userInfo.userId = '10';
    if (change === 'permission') harness.access!.accessCodes = [];
    if (change === 'adapter')
      adapter.value = makeAdapter('asset:category-tag', '标签类目');
    if (change === 'unmount') {
      app!.unmount();
      app = undefined;
    }
    await nextTick();
    pending.resolve(row);
    await loading;
    await nextTick();
    expect(host.textContent).not.toContain('当前类目');
    expect(host.querySelector('[data-centered]')).toBeNull();
    expect(harness.errors).toEqual([]);
  });

  it('无权限和有损编号均不请求详情', async () => {
    harness.access!.accessCodes = [];
    await instance.value!.open(id);
    harness.access!.accessCodes = ['governance:category-term:query'];
    await instance.value!.open(Number.MAX_SAFE_INTEGER + 1);
    expect(adapter.value.get).not.toHaveBeenCalled();
    expect(adapter.value.list).not.toHaveBeenCalled();
    expect(host.querySelector('[data-centered]')).toBeNull();
  });

  it('失败不显示提示条，重新打开和身份校验仍生效', async () => {
    vi.mocked(adapter.value.get).mockRejectedValueOnce(new Error('读取失败'));
    await instance.value!.open(id);
    await nextTick();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    await instance.value!.open(id);
    await flush();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('当前类目');
    vi.mocked(adapter.value.get).mockResolvedValueOnce({
      ...row,
      id: '123',
      name: '错误对象',
    });
    await instance.value!.open(id);
    await nextTick();
    expect(host.textContent).not.toContain('错误对象');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(harness.errors).toEqual([]);
  });
});
