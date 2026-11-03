/* eslint-disable vue/one-component-per-file -- 组件替身只隔离弹层与输入控件，验证真实目录选择状态。 */
import type { App } from 'vue';

import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DatabaseSelect from './database-select.vue';

const state = vi.hoisted(() => ({
  dict: [] as { label: string; value: string }[],
}));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => state.dict }));
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
          props.open ? slots.content?.() : null,
        ]),
  }),
  Input: defineComponent({
    inheritAttrs: false,
    props: { value: { type: String, default: '' }, disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  }),
}));

const catalog = [
  { id: 3, typeName: 'Oracle SID', categoryCode: 'RELATIONAL' },
  { id: 4, typeName: 'Oracle Service', categoryCode: 'RELATIONAL' },
  { id: 5, typeName: 'MySQL', categoryCode: 'RELATIONAL' },
  { id: 9, typeName: 'Doris', categoryCode: 'ANALYTICAL' },
] as FoundationDatabaseTypeApi.DatabaseType[];
const databases: SupplyDataApiApi.DatabaseSimple[] = [
  { id: 2, name: 'SID 业务库', databaseType: 3 },
  { id: '9223372036854775806', name: 'Service 业务库', databaseType: 4 },
  { id: 6, name: 'Doris 仓库', databaseType: 9 },
];
let app: App | undefined;
let host: HTMLDivElement;
let props: {
  catalog: FoundationDatabaseTypeApi.DatabaseType[];
  databases: SupplyDataApiApi.DatabaseSimple[];
  disabled: boolean;
  error: string;
  loading: boolean;
  value: null | SupplyDataApiApi.Id | undefined;
};
type DatabaseChange = (
  value: SupplyDataApiApi.Id | undefined,
  database?: SupplyDataApiApi.DatabaseSimple,
) => void;
let change: ReturnType<typeof vi.fn<DatabaseChange>>;
let retry: ReturnType<typeof vi.fn<() => void>>;
async function flush() {
  await nextTick();
  await nextTick();
}
async function mount(value?: null | SupplyDataApiApi.Id) {
  props = reactive({
    catalog,
    databases,
    value,
    disabled: false,
    error: '',
    loading: false,
  });
  change = vi.fn<DatabaseChange>();
  retry = vi.fn<() => void>();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(DatabaseSelect, {
        ...props,
        'onUpdate:value': (value) => {
          props.value = value;
        },
        onChange: change,
        onRetry: retry,
      }),
  });
  app.mount(host);
  await flush();
}
async function open() {
  host.querySelector<HTMLButtonElement>('[role="combobox"]')!.click();
  await flush();
}
async function click(selector: string) {
  host.querySelector<HTMLButtonElement>(selector)!.click();
  await flush();
}
beforeEach(() => {
  state.dict = reactive([
    { value: 'RELATIONAL', label: '关系型数据库' },
    { value: 'ANALYTICAL', label: '分析型数据库/数据仓库' },
    { value: 'FILE_OBJECT_STORAGE', label: '文件/对象存储' },
    { value: 'NOSQL_CACHE', label: 'NoSQL/缓存' },
    { value: 'MESSAGE_QUEUE', label: '消息队列' },
    { value: 'TIME_SERIES', label: '时序数据库' },
    { value: 'SEMI_STRUCTURED', label: '半结构化存储' },
    { value: 'VECTOR', label: '向量型数据库' },
  ]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('数据 API 管理三级数据源选择器', () => {
  it.each(['catalog', 'product'] as const)(
    '使用 %s 的类型身份渲染选中连接和下拉项的产品图标',
    async (identity) => {
      await mount(7);
      props.catalog =
        identity === 'catalog'
          ? ([
              {
                id: '11',
                typeCode: 'POSTGRESQL',
                typeName: 'PostgreSQL',
                categoryCode: 'RELATIONAL',
                iconPath: '/connection-icons/database.png',
              },
            ] as FoundationDatabaseTypeApi.DatabaseType[])
          : [];
      props.databases = [
        {
          id: 7,
          name: 'postgresql测试库',
          databaseType: 11,
          productType: identity === 'product' ? 'POSTGRES' : undefined,
          categoryCode: 'RELATIONAL',
          iconPath: '/connection-icons/database.png',
        },
      ];
      await flush();
      expect(
        host.querySelector('[role="combobox"] img')?.getAttribute('src'),
      ).toBe('/connection-icons/postgresql.svg');
      await open();
      for (const selector of ['[data-type-id="11"]', '[data-database-id="7"]'])
        expect(host.querySelector(`${selector} img`)?.getAttribute('src')).toBe(
          '/connection-icons/postgresql.svg',
        );
    },
  );

  it('默认全部展示，按 Oracle 精确模板选择后保留原始 ID 类型，清除会通知父组件', async () => {
    await mount();
    await open();
    expect(host.querySelectorAll('[data-category-code]')).toHaveLength(9);
    expect(host.querySelectorAll('[data-database-id]')).toHaveLength(3);
    expect(
      host
        .querySelector('[data-category-code="__ALL__"]')
        ?.getAttribute('aria-selected'),
    ).toBe('true');
    expect(
      host.querySelector<HTMLButtonElement>('[data-type-id="5"]')?.disabled,
    ).toBe(true);
    await click('[data-type-id="4"]');
    expect(host.querySelectorAll('[data-database-id]')).toHaveLength(1);
    await click('[data-database-id="9223372036854775806"]');
    expect(props.value).toBe('9223372036854775806');
    expect(change).toHaveBeenLastCalledWith(
      '9223372036854775806',
      databases[1],
    );
    expect(host.querySelector('[role="combobox"]')?.textContent).toContain(
      'Service 业务库',
    );
    await open();
    expect(
      host.querySelector('[data-type-id="4"]')?.getAttribute('aria-selected'),
    ).toBe('true');
    await click('[data-type-id="3"]');
    await click('[data-database-id="2"]');
    expect(props.value).toBe(2);
    await click('[aria-label="清除数据源"]');
    expect(props.value).toBeUndefined();
    expect(change).toHaveBeenLastCalledWith(undefined);
  });

  it('搜索跨越已选分类但不改变已选值，空结果不伪造选项', async () => {
    await mount(6);
    await open();
    expect(
      host
        .querySelector('[data-category-code="ANALYTICAL"]')
        ?.getAttribute('aria-selected'),
    ).toBe('true');
    const input = host.querySelector('input')!;
    input.value = '业务库';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(props.value).toBe(6);
    expect(change).not.toHaveBeenCalled();
    expect(
      host
        .querySelector('[data-category-code="__ALL__"]')
        ?.getAttribute('aria-selected'),
    ).toBe('true');
    expect(
      host
        .querySelector('[data-type-id="__ALL__"]')
        ?.getAttribute('aria-selected'),
    ).toBe('true');
    expect(host.querySelectorAll('[data-database-id]')).toHaveLength(2);
    input.value = '不存在的名称';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(host.querySelectorAll('[data-database-id]')).toHaveLength(0);
    expect(host.textContent).toContain('没有匹配的连接');
    expect(props.value).toBe(6);
  });

  it('未知已选 ID 明确不可用且不进入可选范围，异步模型和字典能正确回显', async () => {
    state.dict.splice(0);
    await mount('9007199254740993');
    expect(host.textContent).toContain('连接 9007199254740993（不可用）');
    await open();
    expect(
      host.querySelector('[data-database-id="9007199254740993"]'),
    ).toBeNull();
    props.value = '9223372036854775806';
    state.dict.push({ value: 'RELATIONAL', label: '关系型数据库' });
    await flush();
    expect(
      host.querySelector('[data-category-code="RELATIONAL"]')?.textContent,
    ).toContain('关系型数据库');
    expect(
      host.querySelector('[data-type-id="4"]')?.getAttribute('aria-selected'),
    ).toBe('true');
    expect(change).not.toHaveBeenCalled();
  });

  it('加载和错误可见且可重试，禁用关闭弹层，null 值没有虚假回显', async () => {
    await mount(null);
    expect(host.textContent).toContain('请选择数据源');
    expect(host.querySelector('[aria-label="清除数据源"]')).toBeNull();
    props.loading = true;
    await open();
    expect(host.textContent).toContain('正在加载数据源');
    expect(host.querySelector('[data-database-id]')).toBeNull();
    props.loading = false;
    props.error = '加载失败';
    await flush();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '加载失败',
    );
    await click('.database-select__retry');
    expect(retry).toHaveBeenCalledOnce();
    props.disabled = true;
    await flush();
    expect(
      host.querySelector('[data-testid="dataapi-database-picker-panel"]'),
    ).toBeNull();
    expect(
      host.querySelector<HTMLButtonElement>('[role="combobox"]')?.disabled,
    ).toBe(true);
    expect(change).not.toHaveBeenCalled();
  });

  it('方向键跨列移动焦点，Escape 关闭后返回触发器', async () => {
    await mount();
    const trigger = host.querySelector<HTMLButtonElement>('[role="combobox"]')!;
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
    );
    await flush();
    const input = host.querySelector('input')!;
    expect(document.activeElement).toBe(input);
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
    );
    const category = host.querySelector('[data-category-code="__ALL__"]')!;
    expect(document.activeElement).toBe(category);
    category.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }),
    );
    const type = host.querySelector('[data-type-id="__ALL__"]')!;
    expect(document.activeElement).toBe(type);
    type.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
    );
    expect(document.activeElement).toBe(
      host.querySelector('[data-type-id="3"]'),
    );
    document.activeElement!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await flush();
    expect(
      host.querySelector('[data-testid="dataapi-database-picker-panel"]'),
    ).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(change).not.toHaveBeenCalled();
  });
});
