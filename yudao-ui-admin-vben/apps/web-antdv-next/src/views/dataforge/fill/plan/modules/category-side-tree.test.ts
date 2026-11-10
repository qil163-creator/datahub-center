/* eslint-disable vue/one-component-per-file -- 组件桩用于验证分类树的交互。 */
import type { App, VNode } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CategorySideTree from './category-side-tree.vue';

interface TreeNode {
  children?: TreeNode[];
  key: string;
  title: string;
}

const harness = vi.hoisted(() => ({
  expandedKeys: [] as string[],
  expand: (_keys: string[]) => {},
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({
    props: { icon: { type: String, default: '' } },
    setup: (props) => () => h('i', { 'data-icon': props.icon }),
  }),
}));
vi.mock('antdv-next', () => {
  const wrapper = defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  });
  return {
    Button: defineComponent({
      setup: (_, { slots }) => () => h('button', slots.default?.()),
    }),
    Card: wrapper,
    Dropdown: wrapper,
    Empty: () => null,
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup: (props, { emit }) => () =>
        h('input', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
    }),
    Menu: Object.assign(wrapper, { Item: wrapper }),
    Spin: wrapper,
    Tooltip: wrapper,
    Tree: defineComponent({
      props: {
        treeData: { type: Array as () => TreeNode[], default: () => [] },
        expandedKeys: { type: Array as () => string[], default: () => [] },
      },
      emits: ['select', 'update:expandedKeys'],
      setup: (props, { emit, slots }) => () => {
        harness.expandedKeys = props.expandedKeys;
        harness.expand = (keys) => emit('update:expandedKeys', keys);
        const renderNodes = (nodes: TreeNode[]): VNode[] =>
          nodes.flatMap((node) => [
            h(
              'button',
              {
                'data-tree-key': node.key,
                onClick: () => emit('select', [node.key]),
              },
              slots.titleRender?.(node) ?? node.title,
            ),
            ...renderNodes(node.children ?? []),
          ]);
        return h('div', renderNodes(props.treeData));
      },
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

const LARGE_ID = '9223372036854775807';
const CHILD_ID = '9223372036854775806';

function mountTree() {
  const selected = ref<number | string>();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(CategorySideTree, {
          items: [
            { id: LARGE_ID, parentId: 0, name: '教育', sort: 0 },
            { id: CHILD_ID, parentId: LARGE_ID, name: '学生信息', sort: 0 },
            { id: '3', parentId: 0, name: '财务', sort: 1 },
          ],
          selected: selected.value,
          'onUpdate:selected': (value: number | string | undefined) => {
            selected.value = value;
          },
        }),
    }),
  );
  app.mount(host);
  return selected;
}

async function selectNode(key: string) {
  host.querySelector<HTMLButtonElement>(`[data-tree-key="${key}"]`)!.click();
  await nextTick();
}

describe('采集计划分类树', () => {
  it('工具栏不再提供新建分类入口', () => {
    mountTree();
    expect(host.querySelector('[aria-label="新建分类"]')).toBeNull();
    expect(host.querySelector('[aria-label="刷新分类"]')).not.toBeNull();
  });

  it('手动收起后工具栏准确切换，并且全部展开收起保留当前选择', async () => {
    const selected = mountTree();
    await selectNode(CHILD_ID);
    harness.expand([]);
    await nextTick();
    host.querySelector<HTMLButtonElement>('[aria-label="全部展开"]')!.click();
    await nextTick();
    expect(harness.expandedKeys).toEqual(['__all_plans__', LARGE_ID]);
    host.querySelector<HTMLButtonElement>('[aria-label="全部收起"]')!.click();
    await nextTick();
    expect(harness.expandedKeys).toEqual([]);
    expect(selected.value).toBe(CHILD_ID);
  });

  it('以文件夹呈现父节点，以文件呈现叶子分类和未分类入口', () => {
    mountTree();
    const icon = (key: string) =>
      host
        .querySelector<HTMLElement>(`[data-tree-key="${key}"] [data-icon]`)
        ?.dataset.icon;
    expect(icon('__all_plans__')).toBe('lucide:folder-open');
    expect(icon(LARGE_ID)).toBe('lucide:folder-open');
    expect(icon(CHILD_ID)).toBe('lucide:file-text');
    expect(icon('3')).toBe('lucide:file-text');
    expect(icon('0')).toBe('lucide:file-text');
    expect(host.querySelector(String.raw`.md\:w-\[280px\]`)).not.toBeNull();
  });

  it('无损选择分类，并区分未分类与全部计划', async () => {
    const selected = mountTree();
    await selectNode(LARGE_ID);
    expect(selected.value).toBe(LARGE_ID);
    await selectNode('0');
    expect(selected.value).toBe('0');
    await selectNode('__all_plans__');
    expect(selected.value).toBeUndefined();
  });

  it('搜索下级分类保留父级路径并展开，清空后恢复其他分类', async () => {
    mountTree();
    const input = host.querySelector<HTMLInputElement>('input')!;
    input.value = '学生';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    expect(host.querySelector(`[data-tree-key="${LARGE_ID}"]`)).not.toBeNull();
    expect(host.querySelector(`[data-tree-key="${CHILD_ID}"]`)).not.toBeNull();
    expect(host.querySelector('[data-tree-key="3"]')).toBeNull();
    expect(harness.expandedKeys).toContain(LARGE_ID);

    input.value = '';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    expect(host.querySelector('[data-tree-key="3"]')).not.toBeNull();
  });
});
