import type { App, PropType } from 'vue';

import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getStandardElementPage } from '#/api/governance/standardelement';

import PublishedStandardPicker from './published-standard-picker.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessToken: string; refreshToken: string },
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({ accessToken: 'session-1', refreshToken: '' });
  return { useAccessStore: () => harness.access };
});
vi.mock('#/api/governance/standardelement', () => ({
  getStandardElementPage: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Modal: defineComponent({
    emits: ['ok'],
    setup:
      (_, { slots, emit }) =>
      () =>
        h('section', [
          slots.default?.(),
          h('button', { 'data-ok': '', onClick: () => emit('ok') }, '选择'),
        ]),
  }),
  Input: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  }),
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', props.message),
  }),
  Table: defineComponent({
    props: {
      dataSource: {
        type: Array as PropType<GovernanceStandardElementApi.StandardElement[]>,
        default: () => [],
      },
      loading: Boolean,
      pagination: Object,
      rowSelection: Object,
    },
    setup: (props) => () =>
      h('div', { 'data-loading': String(props.loading) }, [
        ...props.dataSource.map((row) =>
          h(
            'button',
            {
              'data-row': row.id,
              onClick: () => props.rowSelection?.onChange([row.id]),
            },
            row.name,
          ),
        ),
        h(
          'button',
          { 'data-next': '', onClick: () => props.pagination?.onChange(2, 10) },
          '下一页',
        ),
      ]),
  }),
}));
let app: App | undefined;
let picker: InstanceType<typeof PublishedStandardPicker>;
let host: HTMLDivElement;
const picked = vi.fn();
const standard = (id: number, name = `标准${id}`) =>
  ({
    id,
    name,
    code: `code${id}`,
    lifecycleStatus: 'PUBLISHED',
    status: 0,
  }) as GovernanceStandardElementApi.StandardElement;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  Object.assign(harness.access!, {
    accessToken: 'session-1',
    refreshToken: '',
  });
  vi.mocked(getStandardElementPage).mockResolvedValue({
    list: [standard(1)],
    total: 120,
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(PublishedStandardPicker, { onPicked: picked });
  picker = app.mount(host) as InstanceType<typeof PublishedStandardPicker>;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('已发布标准远程分页选择', () => {
  it('按已发布启用条件分页，不受前100条限制', async () => {
    picker.open();
    await expect.poll(() => host.textContent).toContain('标准1');
    expect(getStandardElementPage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 10,
      name: undefined,
      lifecycleStatus: 'PUBLISHED',
      status: 0,
    });
    vi.mocked(getStandardElementPage).mockResolvedValueOnce({
      list: [standard(101)],
      total: 120,
    });
    host.querySelector<HTMLButtonElement>('[data-next]')!.click();
    await expect.poll(() => host.textContent).toContain('标准101');
    expect(getStandardElementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 2 }),
    );
    host.querySelector<HTMLButtonElement>('[data-row="101"]')!.click();
    host.querySelector<HTMLButtonElement>('[data-ok]')!.click();
    expect(picked).toHaveBeenCalledWith(expect.objectContaining({ id: 101 }));
  });

  it('名称远程搜索重置页码，旧查询不能覆盖新查询或loading', async () => {
    const old = deferred<{
      list: GovernanceStandardElementApi.StandardElement[];
      total: number;
    }>();
    const latest = deferred<{
      list: GovernanceStandardElementApi.StandardElement[];
      total: number;
    }>();
    vi.mocked(getStandardElementPage)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    picker.open();
    const input = host.querySelector('input')!;
    input.value = '站点';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent === '查询')!
      .click();
    expect(getStandardElementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ name: '站点', pageNo: 1 }),
    );
    old.resolve({ list: [standard(1, '过期结果')], total: 1 });
    await nextTick();
    await nextTick();
    expect(
      host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading,
    ).toBe('true');
    latest.resolve({ list: [standard(2, '最新结果')], total: 1 });
    await expect.poll(() => host.textContent).toContain('最新结果');
    expect(host.textContent).not.toContain('过期结果');
  });

  it('额外拦截草稿和停用标准，读取失败不允许选择', async () => {
    vi.mocked(getStandardElementPage).mockResolvedValueOnce({
      list: [
        { ...standard(1), lifecycleStatus: 'DRAFT' },
        { ...standard(2), status: 1 },
      ],
      total: 2,
    });
    picker.open();
    await nextTick();
    await nextTick();
    expect(host.querySelectorAll('[data-row]')).toHaveLength(0);
    host.querySelector<HTMLButtonElement>('[data-ok]')!.click();
    expect(picked).not.toHaveBeenCalled();
    vi.mocked(getStandardElementPage).mockRejectedValueOnce(
      new Error('读取失败'),
    );
    picker.open();
    await expect.poll(() => vi.mocked(getStandardElementPage).mock.calls.length).toBeGreaterThan(1);
    expect(host.textContent).not.toContain('标准读取失败');
    host.querySelector<HTMLButtonElement>('[data-ok]')!.click();
    expect(picked).not.toHaveBeenCalled();
  });
});
