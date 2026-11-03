import type { App } from 'vue';

import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { createApp, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CategoryTree from './category-tree.vue';

vi.mock('@vben/icons', () => ({ IconifyIcon: () => h('span') }));

let app: App | undefined;
let host: HTMLDivElement;
const selected = vi.fn();
const fixture = (): ModelMaterializedApi.CatNode[] => [
  {
    key: 'group',
    name: '按数据板块',
    type: 'GROUP',
    children: [
      {
        key: 'domain',
        name: '水利监测',
        type: 'DATA_DOMAIN',
        dataDomainId: '9007199254740993127',
      },
    ],
  },
];
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  selected.mockClear();
});

describe('发布模型分类树的返回状态', () => {
  it('刷新目录保留存在的选中节点与收起状态，显式重置才清空', async () => {
    const nodes = ref<ModelMaterializedApi.CatNode[]>([]);
    const tree = ref<InstanceType<typeof CategoryTree>>();
    host = document.createElement('div');
    document.body.append(host);
    app = createApp({
      setup: () => () =>
        h(CategoryTree, {
          ref: tree,
          nodes: nodes.value,
          loading: false,
          failed: false,
          onSelect: selected,
        }),
    });
    app.mount(host);
    nodes.value = fixture();
    await nextTick();
    let items = host.querySelectorAll<HTMLElement>('[role="treeitem"]');
    items[1]!.click();
    await nextTick();
    expect(selected).toHaveBeenCalledWith(
      expect.objectContaining({ dataDomainId: '9007199254740993127' }),
    );
    nodes.value = fixture();
    await nextTick();
    expect(host.querySelector('[aria-selected="true"]')!.textContent).toContain(
      '水利监测',
    );
    items = host.querySelectorAll<HTMLElement>('[role="treeitem"]');
    items[0]!.querySelector<HTMLElement>('.qd-tree-node__expand')!.click();
    await nextTick();
    nodes.value = fixture();
    await nextTick();
    expect(host.querySelectorAll('[role="treeitem"]')).toHaveLength(1);
    tree.value!.reset();
    await nextTick();
    expect(host.querySelectorAll('[role="treeitem"]')).toHaveLength(2);
    expect(host.querySelector('[aria-selected="true"]')).toBeNull();
    nodes.value = [];
    await nextTick();
    nodes.value = fixture();
    await nextTick();
    expect(host.querySelector('[aria-selected="true"]')).toBeNull();
  });
});
