<script setup lang="ts">
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import fileIcon from '../../logictable/assets/tree-file.svg';
import folderCloseIcon from '../../logictable/assets/tree-folder-close.svg';
import folderOpenIcon from '../../logictable/assets/tree-folder-open.svg';
import { QdIcon } from '../../logictable/icons';

type Node = ModelMaterializedApi.CatNode;
const props = defineProps<{
  failed: boolean;
  loading: boolean;
  nodes: Node[];
}>();
const emit = defineEmits<{ retry: []; select: [node?: Node] }>();
const keyword = ref('');
const selected = ref('');
const expanded = ref(new Set<string>());
const DEFAULT_WIDTH = 242;
const width = ref(DEFAULT_WIDTH);
const expandableKeys = computed(() => {
  const keys: string[] = [];
  const pending: Node[] = [...props.nodes];
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
    expandableKeys.value.every((key) => expanded.value.has(key)),
);
function toggleExpandAll() {
  expanded.value = new Set(isExpanded.value ? [] : expandableKeys.value);
}
let beforeSearch: Set<string> | undefined;
watch(keyword, (text, previous) => {
  if (text.trim()) {
    if (!previous.trim()) beforeSearch = new Set(expanded.value);
    expanded.value = new Set(expandableKeys.value);
  } else if (previous.trim()) {
    expanded.value = beforeSearch ?? new Set();
    beforeSearch = undefined;
  }
});

const rows = computed(() => {
  const result: Array<{ level: number; node: Node }> = [];
  const text = keyword.value.trim().toLocaleLowerCase();
  const matches = (node: Node): boolean =>
    node.name.toLocaleLowerCase().includes(text) ||
    (node.children ?? []).some((child) => matches(child));
  const walk = (nodes: Node[], level: number) => {
    for (const node of nodes) {
      if (text && !matches(node)) continue;
      result.push({ node, level });
      if (expanded.value.has(node.key)) walk(node.children ?? [], level + 1);
    }
  };
  walk(props.nodes, 0);
  return result;
});
watch(
  () => props.nodes,
  (nodes, previous) => {
    const keys = (items: Node[]): string[] =>
      items.flatMap((node) => [node.key, ...keys(node.children ?? [])]);
    const available = new Set(keys(nodes));
    const earlier = new Set(keys(previous ?? []));
    expanded.value = new Set([
      ...[...expanded.value].filter((key) => available.has(key)),
      ...nodes.filter((node) => !earlier.has(node.key)).map((node) => node.key),
    ]);
    if (!available.has(selected.value)) selected.value = '';
  },
  { immediate: true },
);
function toggle(node: Node) {
  const next = new Set(expanded.value);
  if (next.has(node.key)) next.delete(node.key);
  else next.add(node.key);
  expanded.value = next;
}
function choose(node: Node) {
  selected.value = node.key;
  if (node.children?.length) toggle(node);
  emit('select', node.type === 'GROUP' ? undefined : node);
}
let startX = 0;
let dragged = false;
function move(event: MouseEvent) {
  if (event.clientX !== startX) dragged = true;
  width.value = Math.min(
    480,
    Math.max(180, width.value + event.clientX - startX),
  );
  startX = event.clientX;
}
function stop() {
  document.removeEventListener('mousemove', move);
  document.removeEventListener('mouseup', stop);
}
function resize(event: MouseEvent) {
  dragged = false;
  startX = event.clientX;
  document.addEventListener('mousemove', move);
  document.addEventListener('mouseup', stop);
}
onBeforeUnmount(stop);
defineExpose({
  reset: () => {
    keyword.value = '';
    selected.value = '';
    expanded.value = new Set(props.nodes.map((node) => node.key));
  },
});
</script>

<template>
  <aside
    class="qd-tree-pane materialized-tree"
    :style="{ width: `${width}px`, display: width === 0 ? 'none' : undefined }"
  >
    <div class="qd-tree-pane__search flex items-center gap-2">
      <label class="min-w-0 flex-1 qd-input"><span class="qd-input__prefix"><QdIcon name="search" /></span><input
          v-model="keyword"
          aria-label="搜索发布模型分类"
          class="qd-input__inner"
          placeholder="搜索类目"
          title="搜索数据板块、主题域或数据连接名"
        /><button
          v-if="keyword"
          class="qd-input__clear"
          type="button"
          aria-label="清空分类搜索"
          @click="keyword = ''"
        >
          ×
        </button></label>
      <TreeToolbar
        :expanded="isExpanded"
        :loading="loading"
        @toggle-expand="toggleExpandAll"
        @refresh="emit('retry')"
      />
    </div>
    <div class="qd-tree-pane__tree" role="tree" aria-label="发布模型分类">
      <div v-if="loading" class="qd-tree-pane__state">加载中…</div>
      <div v-else-if="failed" class="qd-tree-pane__state">
        分类加载失败，<button
          type="button"
          class="qd-link-btn"
          @click="emit('retry')"
        >
          重试
        </button>
      </div>
      <div
        v-for="{ node, level } in loading || failed ? [] : rows"
        :key="node.key"
        class="qd-tree-node"
        :class="{ 'is-current': selected === node.key }"
        role="treeitem"
        tabindex="0"
        :aria-selected="selected === node.key"
        :aria-expanded="
          node.children?.length ? expanded.has(node.key) : undefined
        "
        :style="{ paddingLeft: `${level * 18}px` }"
        @click="choose(node)"
        @keydown.enter="choose(node)"
        @keydown.space.prevent="choose(node)"
      >
        <span
          class="qd-tree-node__expand"
          :class="{
            'is-leaf': !node.children?.length,
            expanded: expanded.has(node.key),
          }"
          @click.stop="toggle(node)"
        ></span>
        <span class="qd-tree-node__label-wrap"><img
            class="qd-tree-node__icon"
            :src="
              node.children?.length ||
              node.type === 'GROUP' ||
              node.type === 'LAYER' ||
              node.type === 'SOURCE_SYSTEM'
                ? expanded.has(node.key)
                  ? folderOpenIcon
                  : folderCloseIcon
                : fileIcon
            "
            alt=""
          /><span class="qd-tree-node__label" :title="node.name">{{
            node.name
          }}</span></span>
      </div>
    </div>
  </aside>
  <div class="qd-resize-bar" @mousedown="resize">
    <SidebarCollapse
      :collapsed="width === 0"
      label="类目"
      @toggle="!dragged && (width = width ? 0 : DEFAULT_WIDTH)"
    />
  </div>
</template>
