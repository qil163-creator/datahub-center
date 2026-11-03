/* eslint-disable vue/one-component-per-file -- 隔离表格和基础控件，验证真实导航树的异步筛选及工具栏状态。 */
import type { App } from 'vue';

import type { MesDvMachineryTypeApi } from '#/api/mes/dv/machinery/type';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import MachineryTypeTree from './tree.vue';

type MachineryType = MesDvMachineryTypeApi.MachineryType;

const harness = vi.hoisted(() => ({
  request: vi.fn(),
  reloadData: vi.fn(),
  setLoading: vi.fn(),
}));

vi.mock('#/api/mes/dv/machinery/type', () => ({
  getMachineryTypeList: harness.request,
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('antdv-next', () => {
  const wrapper = defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  });
  return {
    Card: wrapper,
    Tooltip: wrapper,
    Button: defineComponent({
      props: { disabled: Boolean },
      setup: (props, { attrs, slots }) => () =>
        h('button', { ...attrs, disabled: props.disabled }, slots.default?.()),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value', 'change'],
      setup: (props, { attrs, emit }) => () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) => {
            emit('update:value', (event.target as HTMLInputElement).value);
            emit('change', event);
          },
        }),
    }),
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: () => {
    const state = reactive({ loading: false, rows: [] as MachineryType[] });
    harness.reloadData.mockImplementation(async (rows: MachineryType[]) => {
      state.rows = rows;
    });
    harness.setLoading.mockImplementation((loading: boolean) => {
      state.loading = loading;
    });
    return [
      defineComponent({
        setup: () => () =>
          h(
            'div',
            { 'data-grid-loading': String(state.loading) },
            state.rows.map((row) => h('span', row.name)),
          ),
      }),
      {
        grid: {
          clearCurrentRow: vi.fn(),
          reloadData: harness.reloadData,
          setAllTreeExpand: vi.fn(async () => {}),
          setCurrentRow: vi.fn(async () => {}),
          setTreeExpand: vi.fn(async () => {}),
        },
        setLoading: harness.setLoading,
      },
    ];
  },
}));

let app: App | undefined;
let host: HTMLDivElement;

function deferred() {
  let resolve!: (rows: MachineryType[]) => void;
  const promise = new Promise<MachineryType[]>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function flush() {
  for (let index = 0; index < 6; index++) {
    await Promise.resolve();
    await nextTick();
  }
}

async function search(value: string) {
  const input = host.querySelector<HTMLInputElement>('input')!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.clearAllMocks();
});

describe('设备类型导航树并发筛选', () => {
  it('初始加载与刷新未结束时仍执行最新搜索，旧响应不覆盖数据或提前结束加载', async () => {
    const initial = deferred();
    const firstSearch = deferred();
    const refresh = deferred();
    const latestSearch = deferred();
    harness.request
      .mockReturnValueOnce(initial.promise)
      .mockReturnValueOnce(firstSearch.promise)
      .mockReturnValueOnce(refresh.promise)
      .mockReturnValueOnce(latestSearch.promise);
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(MachineryTypeTree);
    app.mount(host);
    await flush();

    await search('车床');
    expect(harness.request).toHaveBeenCalledTimes(2);
    firstSearch.resolve([{ id: 2, name: '车床', parentId: 0 }]);
    await flush();
    const refreshButton = host.querySelector<HTMLButtonElement>(
      '[aria-label="刷新树"]',
    )!;
    expect(refreshButton.disabled).toBe(false);
    refreshButton.click();
    await flush();
    await search('数控');
    expect(harness.request).toHaveBeenCalledTimes(4);
    expect(harness.request).toHaveBeenLastCalledWith(
      expect.objectContaining({ name: '数控' }),
    );

    initial.resolve([{ id: 1, name: '旧的全量类型', parentId: 0 }]);
    await flush();
    expect(harness.reloadData).toHaveBeenCalledTimes(1);
    expect(refreshButton.disabled).toBe(true);
    expect(host.querySelector('[data-grid-loading="true"]')).not.toBeNull();

    latestSearch.resolve([{ id: 3, name: '数控车床', parentId: 0 }]);
    await flush();
    expect(harness.reloadData).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('数控车床');
    expect(refreshButton.disabled).toBe(false);

    refresh.resolve([{ id: 4, name: '迟到的刷新结果', parentId: 0 }]);
    await flush();
    expect(harness.reloadData).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain('迟到的刷新结果');
    expect(host.textContent).not.toContain('旧的全量类型');
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('数控');
    expect(host.querySelector('[data-grid-loading="false"]')).not.toBeNull();
    expect(harness.setLoading.mock.calls.filter(([loading]) => !loading)).toHaveLength(2);
  });
});
