<script lang="ts" setup>
import type { ModelNavFilter, ModelNavFlatNode } from './layer-navigation';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { handleTree } from '@vben/utils';

import { Input, Spin, Tree } from 'antdv-next';

import { getBizCategoryList } from '#/api/modeling/bizcategory';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import { buildModelNavFlatNodes } from './layer-navigation';

/**
 * 对齐 qData getTreeData：合成「公共层｜应用层」根 + 分类/域｜主题域。
 * 不读数仓分层；点选筛列表；不用树节点冒充 layerId。
 */
defineOptions({ name: 'ModelingLayerTree' });

const emit = defineEmits<{
  (e: 'select', id?: string, filter?: ModelNavFilter): void;
}>();

const selectedId = defineModel<number | string | undefined>('selected');
const navFilter = defineModel<ModelNavFilter>('filter', {
  default: () => ({}),
});

const flatNodes = ref<ModelNavFlatNode[]>([]);
const loading = ref(false);
let requestId = 0;
onBeforeUnmount(() => {
  requestId++;
});
const keyword = ref('');
const expandedKeys = ref<(number | string)[]>([]);
const expandAll = ref(true);
const isExpanded = computed(() => {
  const keys = collectExpandableIds(layerTree.value);
  return keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key));
});

const layerTree = computed(() =>
  handleTree(flatNodes.value.map((n) => ({ ...n }))),
);

function collectExpandableIds(nodes: any[]): (number | string)[] {
  const ids: (number | string)[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      ids.push(node.id, ...collectExpandableIds(node.children));
    }
  }
  return ids;
}

function applyExpandState() {
  expandedKeys.value = expandAll.value
    ? collectExpandableIds(layerTree.value)
    : [];
}

function toggleExpandAll() {
  expandAll.value = !isExpanded.value;
  applyExpandState();
}

function handleSearch() {
  void reload();
  if (keyword.value.trim()) {
    expandAll.value = true;
  }
}

watch(layerTree, applyExpandState);

async function reload() {
  const request = ++requestId;
  loading.value = true;
  try {
    const [bizCategories, subjectDomains] = await Promise.all([
      getBizCategoryList({}),
      getSubjectDomainList({}),
    ]);
    if (request !== requestId) return;
    flatNodes.value = buildModelNavFlatNodes({
      bizCategories: bizCategories || [],
      subjectDomains: subjectDomains || [],
      keyword: keyword.value,
    });
  } finally {
    if (request === requestId) loading.value = false;
  }
}

function handleSelect(keys: (number | string)[]) {
  const id = keys.length > 0 ? String(keys[0]) : undefined;
  selectedId.value = id;
  const hit = flatNodes.value.find((n) => n.id === id);
  const filter = hit?.filter ?? {};
  navFilter.value = { ...filter };
  emit('select', id, filter);
}

function iconFor(node: ModelNavFlatNode & { children?: ModelNavFlatNode[] }) {
  switch (node.nodeType) {
    case 'APP_ROOT':
    case 'PUBLIC_ROOT': {
      return expandedKeys.value.includes(node.id)
        ? 'lucide:folder-open'
        : 'lucide:folder';
    }
    case 'BIZ_CATEGORY':
    case 'DATA_DOMAIN':
    case 'SUBJECT_DOMAIN': {
      if (!node.children?.length) return 'lucide:file-text';
      return expandedKeys.value.includes(node.id)
        ? 'lucide:folder-open'
        : 'lucide:folder';
    }
    default: {
      return 'lucide:circle';
    }
  }
}

function asNode(node: unknown) {
  return node as ModelNavFlatNode & { children?: ModelNavFlatNode[] };
}

defineExpose({ reload });
onMounted(reload);
</script>

<template>
  <div class="layer-tree flex h-full min-h-0 flex-col overflow-hidden">
    <div class="mb-2 flex shrink-0 items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="min-w-0 flex-1"
        placeholder="搜索分类 / 数据域 / 主题域"
        @change="handleSearch"
      >
        <template #prefix>
          <IconifyIcon class="size-4" icon="lucide:search" />
        </template>
      </Input>
      <TreeToolbar
        :expanded="isExpanded"
        :loading="loading"
        @toggle-expand="toggleExpandAll"
        @refresh="reload"
      />
    </div>
    <div class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <Spin :spinning="loading">
        <Tree
          v-if="layerTree.length > 0"
          v-model:expanded-keys="expandedKeys"
          :field-names="{ children: 'children', title: 'title', key: 'id' }"
          :selected-keys="selectedId != null ? [String(selectedId)] : []"
          :tree-data="layerTree as any"
          block-node
          class="pt-2"
          @select="handleSelect"
          @expand="expandAll = isExpanded"
        >
          <template #titleRender="node">
            <span class="layer-tree__node">
              <IconifyIcon
                class="size-4 shrink-0 text-blue-500"
                :icon="iconFor(asNode(node))"
              />
              <span class="truncate">{{ asNode(node).title }}</span>
            </span>
          </template>
        </Tree>
        <div
          v-else-if="!loading"
          class="py-6 text-center text-sm text-gray-400"
        >
          暂无导航节点
        </div>
      </Spin>
    </div>
  </div>
</template>

<style scoped>
.layer-tree__node {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  font-size: 14px;
}
</style>
