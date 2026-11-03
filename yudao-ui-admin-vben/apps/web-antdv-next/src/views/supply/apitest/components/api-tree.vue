<script setup lang="ts">
import type { SupplyApiTestApi } from '#/api/supply/apitest';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Input, Spin, Tree } from 'antdv-next';

import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { parseResourceId } from '#/utils/resource-id';

const props = defineProps<{
  catalog?: SupplyApiTestApi.Catalog;
  loading: boolean;
  selectedApiId?: string;
}>();
const emit = defineEmits<{ refresh: []; select: [id: string] }>();
interface Node {
  key: string;
  title: string;
  path?: string;
  nodeType: 'api' | 'category';
  selectable?: boolean;
  children?: Node[];
}
const keyword = ref('');
const expandedKeys = ref<string[]>([]);
const expanded = ref(true);
let savedExpandedKeys: string[] | undefined;
const rawTree = computed(() => {
  const categories = new Map<string, { node: Node; parentId?: string }>();
  for (const category of props.catalog?.categories ?? []) {
    const id = parseResourceId(category.id);
    if (!id || categories.has(id)) continue;
    categories.set(id, {
      parentId: parseResourceId(category.parentId),
      node: {
        key: `cat-${id}`,
        title: category.name,
        nodeType: 'category',
        selectable: false,
        children: [],
      },
    });
  }
  const visited = new Set<string>();
  const cyclic = new Set<string>();
  for (const id of categories.keys()) {
    if (visited.has(id)) continue;
    const path: string[] = [];
    const positions = new Map<string, number>();
    let ancestor: string | undefined = id;
    while (ancestor && categories.has(ancestor) && !visited.has(ancestor)) {
      const position = positions.get(ancestor);
      if (position !== undefined) {
        for (const cycleId of path.slice(position)) cyclic.add(cycleId);
        break;
      }
      positions.set(ancestor, path.length);
      path.push(ancestor);
      ancestor = categories.get(ancestor)?.parentId;
    }
    for (const pathId of path) visited.add(pathId);
  }
  const roots: Node[] = [];
  for (const [id, { node, parentId }] of categories) {
    const parent = parentId ? categories.get(parentId)?.node : undefined;
    // 环内类目回收到根，普通子类目仍保留原有层级；每个类目只连接一次。
    if (parent && !cyclic.has(id)) parent.children!.push(node);
    else roots.push(node);
  }
  const unclassified: Node = {
    key: 'unclassified',
    title: '未分类',
    nodeType: 'category',
    selectable: false,
    children: [],
  };
  const apiIds = new Set<string>();
  for (const api of props.catalog?.apis ?? []) {
    const id = parseResourceId(api.id);
    if (!id || apiIds.has(id)) continue;
    apiIds.add(id);
    const categoryId = parseResourceId(api.categoryId);
    const parent =
      (categoryId ? categories.get(categoryId)?.node : undefined) ??
      unclassified;
    parent.children!.push({
      key: `api-${id}`,
      title: api.name,
      path: api.path,
      nodeType: 'api',
    });
  }
  if (unclassified.children!.length > 0) roots.push(unclassified);
  return roots;
});
const treeData = computed(() => {
  const search = keyword.value.trim().toLowerCase();
  if (!search) return rawTree.value;
  const filtered = new Map<Node, Node>();
  const pending = rawTree.value.map((node) => ({ node, processed: false }));
  while (pending.length > 0) {
    const { node, processed } = pending.pop()!;
    if (`${node.title} ${node.path ?? ''}`.toLowerCase().includes(search)) {
      filtered.set(node, node);
    } else if (processed) {
      const children = (node.children ?? []).flatMap((child) => {
        const match = filtered.get(child);
        return match ? [match] : [];
      });
      if (children.length > 0) filtered.set(node, { ...node, children });
    } else {
      pending.push({ node, processed: true });
      for (const child of node.children ?? []) {
        pending.push({ node: child, processed: false });
      }
    }
  }
  return rawTree.value.flatMap((node) => {
    const match = filtered.get(node);
    return match ? [match] : [];
  });
});
function expandKeys(nodes: Node[]): string[] {
  const keys: string[] = [];
  const pending = [...nodes];
  while (pending.length > 0) {
    const node = pending.pop()!;
    if (node.children?.length) {
      keys.push(node.key);
      for (const child of node.children) pending.push(child);
    }
  }
  return keys;
}
function toggleExpanded() {
  expanded.value = !allExpanded.value;
  expandedKeys.value = expanded.value ? expandKeys(treeData.value) : [];
}
const allExpanded = computed(() => {
  const keys = expandKeys(treeData.value);
  return (
    keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key))
  );
});
watch(
  [rawTree, () => keyword.value.trim()],
  ([roots, search], [previousRoots, previousSearch]) => {
    if (search) {
      if (!previousSearch) savedExpandedKeys = [...expandedKeys.value];
      expandedKeys.value = expandKeys(treeData.value);
    } else if (previousSearch) {
      const available = new Set(expandKeys(roots));
      expandedKeys.value = (savedExpandedKeys ?? []).filter((key) =>
        available.has(key),
      );
      savedExpandedKeys = undefined;
    } else if (roots !== previousRoots) {
      const available = expandKeys(roots);
      if (previousRoots?.length) {
        expandedKeys.value = expandedKeys.value.filter((key) => available.includes(key));
      } else {
        expandedKeys.value = expanded.value ? available : [];
      }
    }
  },
  { immediate: true },
);
watch(
  () => props.catalog,
  (catalog) => {
    if (!catalog) {
      keyword.value = '';
      expanded.value = true;
      expandedKeys.value = [];
      savedExpandedKeys = undefined;
    }
  },
);
function select(keys: (number | string)[]) {
  const key = String(keys[0] ?? '');
  const id = key.startsWith('api-') ? parseResourceId(key.slice(4)) : undefined;
  if (id) emit('select', id);
}
</script>

<template>
  <div class="test-tree">
    <div class="test-tree__search">
      <Input
        v-model:value="keyword"
        allow-clear
        class="test-tree__input"
        placeholder="搜索名称或路径"
        aria-label="搜索 API 名称或路径"
        title="搜索 API 名称或路径"
      >
        <template #prefix><IconifyIcon icon="lucide:search" /></template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        refresh-label="刷新 API 树"
        @toggle-expand="toggleExpanded"
        @refresh="emit('refresh')"
      />
    </div>
    <Spin :spinning="loading" class="test-tree__body subtle-tree-scrollbar">
      <Tree
        v-if="treeData.length"
        v-model:expanded-keys="expandedKeys"
        :selected-keys="selectedApiId ? [`api-${selectedApiId}`] : []"
        :tree-data="treeData"
        block-node
        @select="select"
      >
        <template #titleRender="node">
          <span class="test-tree__node" :title="node.path || node.title"><IconifyIcon
              :icon="node.nodeType === 'api' ? 'lucide:link' : 'lucide:folder'"
              class="size-4 shrink-0"
              :class="
                node.nodeType === 'api' ? 'text-amber-500' : 'text-blue-500'
              "
            /><span>{{ node.title }}</span></span>
        </template>
      </Tree>
      <div v-else-if="!loading" class="test-tree__empty">
        {{ keyword ? '没有匹配的 API' : '暂无可测试的已上线 API' }}
      </div>
    </Spin>
  </div>
</template>

<style scoped>
.test-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.test-tree__search {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding-bottom: 8px;
}

.test-tree__input {
  flex: 1 1 140px;
  min-width: min(140px, 100%);
}

.test-tree__body {
  flex: 1;
  min-height: 0;
  padding-top: 8px;
  overflow: hidden auto;
}

.test-tree__node {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.test-tree__node > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.test-tree__empty {
  padding: 24px 4px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

:deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

:deep(.ant-tree-treenode) {
  width: 100%;
}

:deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

:deep(.ant-tree-title) {
  display: block;
  width: 100%;
}
</style>
