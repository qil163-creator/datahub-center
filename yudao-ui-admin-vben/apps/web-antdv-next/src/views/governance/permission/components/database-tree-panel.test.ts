/* eslint-disable vue/one-component-per-file -- Exercise the real tree state with lightweight UI controls. */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import DatabaseTree from './database-tree-panel.vue';

interface Node {
  children?: Node[];
  key: string;
  nodeType: string;
  raw?: Record<string, unknown>;
}
interface TreeState {
  expandedKeys: string[];
  loadData: (node: Node) => Promise<void>;
  selectedKeys: string[];
  treeData: Node[];
}
const api = vi.hoisted(() => ({ databases: vi.fn(), tables: vi.fn() }));
let tree: TreeState;
let select: (node: Node) => void;

vi.mock('#/api/governance/permission', () => ({
  getPermissionDatabaseList: api.databases,
  getPermissionTableList: api.tables,
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('antdv-next', () => {
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Spin: wrapper,
    Tag: wrapper,
    Tooltip: wrapper,
    message: { warning: vi.fn() },
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
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
    Tree: defineComponent({
      props: {
        expandedKeys: { type: Array as PropType<string[]>, required: true },
        loadData: {
          type: Function as PropType<TreeState['loadData']>,
          required: true,
        },
        selectedKeys: { type: Array as PropType<string[]>, required: true },
        treeData: { type: Array as PropType<Node[]>, required: true },
      },
      emits: ['select'],
      setup(props, { emit }) {
        tree = props;
        select = (node) => emit('select', [node.key], { node });
        return () => h('div');
      },
    }),
  };
});

const database = { id: '9007199254740993', name: 'warehouse' };
const table = {
  schemaName: 'public',
  tableName: 'orders',
  tableReference: 'public.orders',
};
let app: App;
let host: HTMLDivElement;
let instance: { reload: () => Promise<void> };
async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
function click(label: string) {
  (host.querySelector(`[aria-label="${label}"]`) as HTMLButtonElement).click();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(async () => {
  vi.clearAllMocks();
  api.databases.mockResolvedValue([database]);
  api.tables.mockResolvedValue([table]);
  host = document.createElement('div');
  app = createApp(DatabaseTree);
  instance = app.mount(host) as unknown as typeof instance;
  await flush();
});
afterEach(() => {
  app.unmount();
  host.remove();
});

it('loads lazy tables when expanding all and preserves search, selection and collapse on refresh', async () => {
  click('全部展开');
  await flush();
  expect(api.tables).toHaveBeenCalledWith(database.id, { silentError: true });
  select(tree.treeData[0]!.children![0]!);
  await flush();
  const selected = [...tree.selectedKeys];
  const input = host.querySelector('input')!;
  input.value = 'orders';
  input.dispatchEvent(new Event('input'));
  await flush();
  click('全部收起');
  click('刷新树');
  await flush();
  expect(api.databases).toHaveBeenCalledTimes(2);
  expect(api.tables).toHaveBeenCalledTimes(2);
  expect(tree.expandedKeys).toEqual([]);
  expect(tree.selectedKeys).toEqual(selected);
  expect(input.value).toBe('orders');
});

it('ignores a previous refresh that finishes after the latest one', async () => {
  const old = deferred<(typeof database)[]>();
  const latest = deferred<(typeof database)[]>();
  api.databases
    .mockReturnValueOnce(old.promise)
    .mockReturnValueOnce(latest.promise);
  const first = instance.reload();
  const second = instance.reload();
  latest.resolve([{ ...database, id: '9007199254740995' }]);
  await second;
  old.resolve([database]);
  await first;
  await flush();
  expect(tree.treeData.map((node) => node.key)).toEqual([
    'db-9007199254740995',
  ]);
});

it('does not attach stale lazy-table responses to the refreshed database tree', async () => {
  const old = deferred<(typeof table)[]>();
  api.tables.mockReturnValueOnce(old.promise);
  click('全部展开');
  await flush();
  await instance.reload();
  old.resolve([{ ...table, tableName: 'old', tableReference: 'public.old' }]);
  await flush();
  expect(tree.treeData[0]!.children![0]!.raw?.tableName).toBe('orders');
});

it('blocks expansion and lazy loads on old roots while refresh is pending, then loads the current search', async () => {
  const pending = deferred<(typeof database)[]>();
  api.databases.mockReturnValueOnce(pending.promise);
  const oldRoot = tree.treeData[0]!;
  const refreshing = instance.reload();
  await flush();
  click('全部展开');
  await expect(tree.loadData(oldRoot)).rejects.toMatchObject({
    name: 'AbortError',
  });
  const input = host.querySelector('input')!;
  input.value = 'orders';
  input.dispatchEvent(new Event('input'));
  await flush();
  expect(api.tables).not.toHaveBeenCalled();
  expect(tree.expandedKeys).toEqual([]);
  pending.resolve([database]);
  await refreshing;
  await flush();
  expect(api.tables).toHaveBeenCalledOnce();
  expect(oldRoot.children).toBeUndefined();
  expect(tree.treeData[0]!.children![0]!.raw?.tableName).toBe('orders');
  expect(input.value).toBe('orders');
  click('全部展开');
  await flush();
  expect(tree.expandedKeys).toEqual([`db-${database.id}`]);
});
