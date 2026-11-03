/* eslint-disable vue/one-component-per-file -- Isolated controls exercise the real binding modal lifecycle. */
import type { App, PropType } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetTermApi } from '#/api/asset/term';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { bindAssetTermColumn, getSimpleAssetTermList } from '#/api/asset/term';

import TermBindForm from './term-bind-form.vue';

interface ModalOptions {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => Promise<void>;
}

interface Choice {
  disabled?: boolean;
  label: string;
  value: string;
}

const state = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
  data: {} as { assetId: DataAssetApi.Id; column: DataAssetApi.MetadataColumn },
  options: {} as ModalOptions,
  locked: false,
  success: vi.fn(),
  close: vi.fn(),
}));

vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('#/api/asset/term', () => ({
  bindAssetTermColumn: vi.fn(),
  getSimpleAssetTermList: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    state.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', slots.default?.()),
      }),
      {
        getData: () => state.data,
        close: state.close,
        lock: () => {
          state.locked = true;
        },
        unlock: () => {
          state.locked = false;
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Form: container,
    FormItem: container,
    message: { success: vi.fn() },
    Select: defineComponent({
      props: {
        value: { type: String, default: undefined },
        loading: Boolean,
        options: { type: Array as PropType<Choice[]>, default: () => [] },
      },
      emits: ['change', 'search'],
      setup:
        (props, { emit }) =>
        () =>
          h('div', [
            h('input', {
              onInput: (event: Event) =>
                emit('search', (event.target as HTMLInputElement).value),
            }),
            h('span', { 'data-loading': '' }, String(props.loading)),
            h(
              'select',
              {
                value: props.value ?? '',
                onChange: (event: Event) =>
                  emit(
                    'change',
                    (event.target as HTMLSelectElement).value || undefined,
                  ),
              },
              [
                h('option', { value: '' }, '清空'),
                ...props.options.map((choice) =>
                  h(
                    'option',
                    { value: choice.value, disabled: choice.disabled },
                    choice.label,
                  ),
                ),
              ],
            ),
          ]),
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
}

function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(TermBindForm, { onSuccess: state.success });
  app.mount(host);
}

function choose(value: string) {
  const select = host.querySelector('select')!;
  select.value = value;
  select.dispatchEvent(new Event('change'));
}

function search(value: string) {
  const input = host.querySelector('input')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

function deferred() {
  let resolve!: (value: AssetTermApi.Term[]) => void;
  const promise = new Promise<AssetTermApi.Term[]>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  state.access = reactive({ accessToken: '1', refreshToken: '' });
  state.data = {
    assetId: '9007199254740993',
    column: { id: '9007199254740995', columnName: 'customer_id' },
  };
  state.locked = false;
  state.close.mockImplementation(() => state.options.onOpenChange(false));
  vi.mocked(getSimpleAssetTermList).mockResolvedValue([]);
  vi.mocked(bindAssetTermColumn).mockResolvedValue(true);
  mount();
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  vi.useRealTimers();
});

describe('资产字段统一使用治理业务术语', () => {
  it('通过资产绑定接口提交无损大 ID，显示治理定义', async () => {
    vi.mocked(getSimpleAssetTermList).mockResolvedValue([
      {
        id: '9007199254740997',
        name: '客户编号',
        description: '客户的唯一标识',
        status: 0,
      },
    ]);
    await state.options.onOpenChange(true);
    await flush();
    choose('9007199254740997');
    await flush();
    expect(host.textContent).toContain('客户的唯一标识');
    expect(host.textContent).toContain('数据治理 → 业务术语');
    expect(host.textContent).not.toContain('新增术语');
    await state.options.onConfirm();
    expect(bindAssetTermColumn).toHaveBeenCalledExactlyOnceWith({
      assetId: '9007199254740993',
      metaColumnId: '9007199254740995',
      termId: '9007199254740997',
    });
    expect(state.success).toHaveBeenCalledOnce();
    expect(state.locked).toBe(false);
  });

  it('数字与字符串 ID 对齐，未改动原绑定时不重复提交', async () => {
    state.data.column.termId = 7;
    state.data.column.termName = '旧名称';
    vi.mocked(getSimpleAssetTermList).mockResolvedValue([
      { id: '7', name: '统一名称', status: 0 },
    ]);
    await state.options.onOpenChange(true);
    await flush();
    expect(host.querySelector('select')!.value).toBe('7');
    expect(host.textContent).toContain('统一名称');
    expect(host.textContent).not.toContain('旧名称');
    await state.options.onConfirm();
    expect(bindAssetTermColumn).not.toHaveBeenCalled();
  });

  it.each(['已停用术语', undefined])(
    '不可选或删除的绑定保留回显并可清空：%s',
    async (name) => {
      state.data.column.termId = '9007199254740997';
      state.data.column.termName = name;
      await state.options.onOpenChange(true);
      await flush();
      expect(host.querySelector('select')!.value).toBe('9007199254740997');
      expect(host.textContent).toContain(name || '术语 #9007199254740997');
      expect(host.textContent).toContain('当前绑定术语不在候选列表中');
      choose('');
      await state.options.onConfirm();
      expect(bindAssetTermColumn).toHaveBeenCalledExactlyOnceWith({
        assetId: '9007199254740993',
        metaColumnId: '9007199254740995',
        termId: undefined,
      });
    },
  );

  it('快速搜索仅显示新结果，旧请求不能清除 loading 或覆盖新候选', async () => {
    vi.useFakeTimers();
    await state.options.onOpenChange(true);
    const old = deferred();
    const latest = deferred();
    vi.mocked(getSimpleAssetTermList)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    search('old');
    await vi.advanceTimersByTimeAsync(300);
    search('new');
    await vi.advanceTimersByTimeAsync(300);
    old.resolve([{ id: '1', name: '旧结果' }]);
    await flush();
    expect(host.querySelector('[data-loading]')!.textContent).toBe('true');
    expect(host.textContent).not.toContain('旧结果');
    latest.resolve([{ id: '2', name: '新结果' }]);
    await flush();
    expect(host.textContent).toContain('新结果');
    expect(host.querySelector('[data-loading]')!.textContent).toBe('false');
  });

  it.each(['close', 'session'])(
    '关闭或切换登录会话后拒绝旧候选与原字段提交：%s',
    async (action) => {
      const pending = deferred();
      vi.mocked(getSimpleAssetTermList).mockReturnValueOnce(pending.promise);
      const opening = state.options.onOpenChange(true);
      if (action === 'close') await state.options.onOpenChange(false);
      else state.access.refreshToken = '2';
      await flush();
      pending.resolve([{ id: '1', name: '旧会话术语' }]);
      await opening;
      await flush();
      expect(host.textContent).not.toContain('旧会话术语');
      await state.options.onConfirm();
      expect(bindAssetTermColumn).not.toHaveBeenCalled();
    },
  );

  it('加载失败显示失败语义，重开后恢复选择', async () => {
    vi.mocked(getSimpleAssetTermList).mockRejectedValueOnce(
      new Error('failed'),
    );
    await state.options.onOpenChange(true);
    await flush();
    expect(host.textContent).toContain('术语加载失败');
    await state.options.onOpenChange(false);
    vi.mocked(getSimpleAssetTermList).mockResolvedValue([
      { id: 1, name: '可选术语', status: 0 },
    ]);
    await state.options.onOpenChange(true);
    await flush();
    expect(host.textContent).not.toContain('术语加载失败');
    expect(host.textContent).toContain('可选术语');
  });
});
