<script setup lang="ts">
import type { ModelingLogicTableApi } from '#/api/modeling/logictable';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import fileIcon from '../assets/tree-file.svg';
import folderCloseIcon from '../assets/tree-folder-close.svg';
import folderOpenIcon from '../assets/tree-folder-open.svg';
import { QdIcon } from '../icons';

type CatNode = ModelingLogicTableApi.CatNode;

interface FlatNode {
  key: string;
  label: string;
  level: number;
  leaf: boolean;
  expanded: boolean;
  node?: CatNode;
}

const props = defineProps<{
  failed?: boolean;
  loading?: boolean;
  nodes: CatNode[];
  placeholder: string;
  rootLabel: string;
}>();
const emit = defineEmits<{
  retry: [];
  select: [node: CatNode | undefined];
}>();

const ROOT_KEY = '__root__';
const DEFAULT_WIDTH = 242;
const keyword = ref('');
const currentKey = ref<string>();
const expandedKeys = ref(new Set<string>([ROOT_KEY]));
const width = ref(DEFAULT_WIDTH);
const expandableKeys = computed(() => {
  const keys: string[] = [ROOT_KEY];
  const pending: CatNode[] = [...props.nodes];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const node = pending.pop()!;
    if (visited.has(node.key)) continue;
    visited.add(node.key);
    if (node.children?.length) {
      keys.push(node.key);
      pending.push(...node.children);
    }
  }
  return keys;
});
const isExpanded = computed(
  () =>
    expandableKeys.value.length > 0 &&
    expandableKeys.value.every((key) => expandedKeys.value.has(key)),
);
function toggleExpandAll() {
  expandedKeys.value = new Set(isExpanded.value ? [] : expandableKeys.value);
}
let beforeSearch: Set<string> | undefined;
watch(keyword, (text, previous) => {
  if (text.trim()) {
    if (!previous.trim()) beforeSearch = new Set(expandedKeys.value);
    expandedKeys.value = new Set(expandableKeys.value);
  } else if (previous.trim()) {
    expandedKeys.value = beforeSearch ?? new Set();
    beforeSearch = undefined;
  }
});

function matches(node: CatNode, text: string): boolean {
  return (
    node.name.includes(text) ||
    (node.children ?? []).some((child) => matches(child, text))
  );
}

/** 拍平成可见行：搜索时保留命中节点及其祖先并全部展开。 */
const rows = computed<FlatNode[]>(() => {
  const text = keyword.value.trim();
  const result: FlatNode[] = [];
  const rootExpanded = expandedKeys.value.has(ROOT_KEY);
  result.push({
    key: ROOT_KEY,
    label: props.rootLabel,
    level: 0,
    leaf: false,
    expanded: rootExpanded,
  });
  if (!rootExpanded) return result;
  const walk = (list: CatNode[], level: number) => {
    for (const node of list) {
      if (text && !matches(node, text)) continue;
      const children = node.children ?? [];
      const expanded = expandedKeys.value.has(node.key);
      result.push({
        key: node.key,
        label: node.name,
        level,
        leaf: children.length === 0,
        expanded,
        node,
      });
      if (expanded && children.length > 0) walk(children, level + 1);
    }
  };
  walk(props.nodes, 1);
  return result;
});

function toggle(row: FlatNode) {
  if (row.leaf) return;
  const next = new Set(expandedKeys.value);
  if (next.has(row.key)) next.delete(row.key);
  else next.add(row.key);
  expandedKeys.value = next;
}

function choose(row: FlatNode) {
  currentKey.value = row.key;
  if (!row.leaf && row.level > 0) {
    // 与 el-tree 一致：点击节点内容同时展开/收起
    toggle(row);
  } else if (row.level === 0) {
    toggle(row);
  }
  emit('select', row.node);
}

function iconOf(row: FlatNode) {
  if (row.leaf) return fileIcon;
  return row.expanded ? folderOpenIcon : folderCloseIcon;
}

/** 重置：取消选中并清空关键字。 */
function reset() {
  currentKey.value = undefined;
  keyword.value = '';
}
defineExpose({ reset });

function containsKey(nodes: CatNode[], key: string): boolean {
  return nodes.some(
    (node) => node.key === key || containsKey(node.children ?? [], key),
  );
}

watch(
  () => props.nodes,
  () => {
    if (
      currentKey.value &&
      currentKey.value !== ROOT_KEY &&
      !containsKey(props.nodes, currentKey.value)
    ) {
      currentKey.value = undefined;
    }
  },
);

// 拖拽调整左侧宽度 / 折叠
let startX = 0;
let dragged = false;
function onMove(event: MouseEvent) {
  if (event.clientX !== startX) dragged = true;
  width.value = Math.max(0, width.value + event.clientX - startX);
  startX = event.clientX;
}
function stopResize() {
  document.removeEventListener('mousemove', onMove);
  document.removeEventListener('mouseup', stopResize);
}
function startResize(event: MouseEvent) {
  dragged = false;
  startX = event.clientX;
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', stopResize);
}
function toggleCollapse() {
  if (dragged) return;
  width.value = width.value === 0 ? DEFAULT_WIDTH : 0;
}
onBeforeUnmount(stopResize);
</script>

<template>
  <aside
    v-show="width !== 0"
    class="qd-tree-pane"
    :style="{ width: `${width}px` }"
  >
    <div class="qd-tree-pane__search flex items-center gap-2">
      <label class="min-w-0 flex-1 qd-input qd-input--prefix">
        <span class="qd-input__prefix"><QdIcon name="search" /></span>
        <input
          v-model="keyword"
          :aria-label="placeholder"
          class="qd-input__inner"
          placeholder="搜索类目"
          :title="placeholder"
          type="text"
        />
        <button
          v-if="keyword"
          type="button"
          class="qd-input__clear"
          aria-label="清空"
          @click="keyword = ''"
        >
          ×
        </button>
      </label>
      <TreeToolbar
        :expanded="isExpanded"
        :loading="loading"
        @toggle-expand="toggleExpandAll"
        @refresh="emit('retry')"
      />
    </div>
    <div class="qd-tree-pane__tree subtle-tree-scrollbar" role="tree">
      <div v-if="loading" class="qd-tree-pane__state">加载中…</div>
      <template v-else-if="failed"></template>
      <template v-else>
        <div
          v-for="row in rows"
          :key="row.key"
          class="qd-tree-node"
          :class="{ 'is-current': currentKey === row.key }"
          role="treeitem"
          :aria-expanded="row.leaf ? undefined : row.expanded"
          :aria-selected="currentKey === row.key"
          tabindex="0"
          :style="{ paddingLeft: `${row.level * 18}px` }"
          @click="choose(row)"
          @keydown.enter="choose(row)"
        >
          <span
            class="qd-tree-node__expand"
            :class="{ 'is-leaf': row.leaf, expanded: row.expanded }"
            @click.stop="toggle(row)"
          ></span>
          <span class="qd-tree-node__label-wrap">
            <img class="qd-tree-node__icon" :src="iconOf(row)" alt="" />
            <span class="qd-tree-node__label" :title="row.label">{{
              row.label
            }}</span>
          </span>
        </div>
      </template>
    </div>
  </aside>
  <div class="qd-resize-bar" @mousedown="startResize">
    <SidebarCollapse
      :collapsed="width === 0"
      label="类目"
      @toggle="toggleCollapse"
    />
  </div>
</template>
