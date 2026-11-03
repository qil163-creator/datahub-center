/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身仅保留选择器依赖的交互。 */
import type { App } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';

import DatabaseConnectionPicker from './database-connection-picker.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Popover: defineComponent({
    props: { open: Boolean },
    emits: ['update:open'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('div', [
          h(
            'div',
            { onClick: () => emit('update:open', !props.open) },
            slots.default?.(),
          ),
          props.open ? slots.content?.() : undefined,
        ]),
  }),
  Input: defineComponent({
    props: { value: String },
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
      (_props, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Spin: defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));

const types = [
  {
    id: '10',
    typeCode: 'MYSQL',
    typeName: 'MySQL',
    productId: 'MYSQL',
    categoryCode: 'RELATIONAL',
  },
  {
    id: '11',
    typeCode: 'ORACLE',
    typeName: 'Oracle',
    productId: 'ORACLE',
    categoryCode: 'RELATIONAL',
  },
  {
    id: '12',
    typeCode: 'DORIS',
    typeName: 'Doris',
    productId: 'DORIS',
    categoryCode: 'ANALYTICAL',
  },
] as FoundationDatabaseTypeApi.DatabaseType[];
const databases: FoundationDatabaseApi.DatabaseSimple[] = [
  {
    id: '9007199254740993',
    name: '河道水情系统',
    databaseType: '10',
    productId: 'MYSQL',
  },
  { id: '2', name: '分析仓库', databaseType: '12', productId: 'DORIS' },
  { id: '3', name: '另一连接', productId: 'MYSQL' },
];
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 5; index++) await nextTick();
}
async function mount(value?: string, items = databases) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({
    value,
    databases: items,
    disabled: false,
    loading: false,
  });
  const onChange = vi.fn();
  app = createApp({
    render: () =>
      h(DatabaseConnectionPicker, {
        ...state,
        'onUpdate:value': (next) => {
          state.value = next;
        },
        onChange,
      }),
  });
  app.mount(host);
  await flush();
  return { state, onChange };
}
async function click(text: string, selector = 'button') {
  const button = [...host.querySelectorAll<HTMLButtonElement>(selector)].find(
    (item) => item.textContent?.includes(text),
  );
  expect(button).toBeTruthy();
  button!.click();
  await flush();
}
async function open() {
  host.querySelector<HTMLButtonElement>('[role="combobox"]')!.click();
  await flush();
}
beforeEach(() => {
  vi.mocked(getDatabaseTypeSimpleList).mockResolvedValue(types);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.clearAllMocks();
});

describe('表输入数据库连接选择器', () => {
  it('依据真实类型分类和计数，零连接类型仍可浏览', async () => {
    await mount();
    await open();
    expect(
      host.querySelector('[aria-label="数据库连接分类"]')?.textContent,
    ).toContain('关系型数据库(2)');
    expect(
      host.querySelector('[aria-label="数据库连接分类"]')?.textContent,
    ).toContain('分析型数据库/数据仓库(1)');
    await click('关系型数据库');
    expect(
      host.querySelector('[aria-label="数据库连接类型"]')?.textContent,
    ).toContain('MySQL(2)');
    expect(
      host.querySelector('[aria-label="数据库连接类型"]')?.textContent,
    ).toContain('Oracle(0)');
    await click('Oracle');
    expect(host.querySelector('[role="listbox"]')?.textContent).toContain(
      '暂无数据连接',
    );
    await click('MySQL');
    expect(host.querySelector('[role="listbox"]')?.textContent).toContain(
      '河道水情系统',
    );
    expect(host.querySelector('[role="listbox"]')?.textContent).not.toContain(
      '分析仓库',
    );
  });

  it('搜索连接名并选择后保持 Long ID 无损，重新打开定位原分类与类型', async () => {
    const { state, onChange } = await mount();
    await open();
    const search = host.querySelector<HTMLInputElement>(
      '[aria-label="搜索数据连接"]',
    )!;
    search.value = '河道';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(1);
    await click('河道水情系统', '[role="option"]');
    expect(state.value).toBe('9007199254740993');
    expect(onChange).toHaveBeenCalledWith('9007199254740993');
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    await open();
    expect(
      host.querySelector('[aria-label="数据库连接分类"] .is-active')
        ?.textContent,
    ).toContain('关系型数据库');
    expect(
      host.querySelector('[aria-label="数据库连接类型"] .is-active')
        ?.textContent,
    ).toContain('MySQL');
    expect(
      host.querySelector('[role="option"][aria-selected="true"]')?.textContent,
    ).toContain('河道水情系统');
  });

  it('未知连接回显并支持显式清除，只读和加载期间阻止变更', async () => {
    const { state, onChange } = await mount('9007199254740995');
    expect(host.querySelector('[role="combobox"]')?.textContent).toContain(
      '未知连接（9007199254740995）',
    );
    state.disabled = true;
    await flush();
    host
      .querySelector<HTMLButtonElement>('[aria-label="清除数据连接"]')!
      .click();
    await open();
    expect(state.value).toBe('9007199254740995');
    expect(onChange).not.toHaveBeenCalled();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    state.disabled = false;
    state.loading = true;
    await flush();
    await open();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    state.loading = false;
    await flush();
    host
      .querySelector<HTMLButtonElement>('[aria-label="清除数据连接"]')!
      .click();
    await flush();
    expect(state.value).toBeUndefined();
    expect(onChange).toHaveBeenCalledWith(undefined);
  });

  it('类型元数据失败时保留全部真实连接可选并提供重试', async () => {
    vi.mocked(getDatabaseTypeSimpleList).mockRejectedValueOnce(
      new Error('failed'),
    );
    const { state } = await mount();
    await open();
    expect(host.textContent).toContain('连接类型加载失败');
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(3);
    await click('重试');
    expect(host.textContent).not.toContain('连接类型加载失败');
    await click('河道水情系统', '[role="option"]');
    expect(state.value).toBe('9007199254740993');
  });

  it('关闭浮层后丢弃旧请求结果，避免污染重新打开的分类', async () => {
    let complete!: (value: FoundationDatabaseTypeApi.DatabaseType[]) => void;
    vi.mocked(getDatabaseTypeSimpleList).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    await mount();
    await open();
    host
      .querySelector('[role="dialog"]')!
      .dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
    await flush();
    await open();
    complete([{ ...types[0]!, typeName: '过期类型' }]);
    await flush();
    expect(host.textContent).not.toContain('过期类型');
    expect(
      host.querySelector('[aria-label="数据库连接类型"]')?.textContent,
    ).toContain('Oracle');
  });

  it('类型请求完成时保留用户已经切换的浏览分类', async () => {
    let complete!: (value: FoundationDatabaseTypeApi.DatabaseType[]) => void;
    vi.mocked(getDatabaseTypeSimpleList).mockImplementationOnce(
      () => new Promise((resolve) => { complete = resolve; }),
    );
    await mount('9007199254740993');
    await open();
    await click('分析型数据库/数据仓库');
    complete(types);
    await flush();
    expect(
      host.querySelector('[aria-label="数据库连接分类"] .is-active')?.textContent,
    ).toContain('分析型数据库/数据仓库');
    expect(host.querySelector('[role="listbox"]')?.textContent).toContain('分析仓库');
    expect(host.querySelector('[role="listbox"]')?.textContent).not.toContain('河道水情系统');
  });
});
