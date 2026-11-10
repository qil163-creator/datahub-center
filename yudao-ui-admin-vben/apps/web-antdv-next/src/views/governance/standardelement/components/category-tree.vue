<script setup lang="ts">
import type { StandardCategory, StandardId } from '../../standardcommon/data';

import { computed, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Input, Spin, Tree } from 'antdv-next';

import { getStandardElementCategories } from '#/api/governance/standardelement';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import { standardCategoryTree } from '../../standardcommon/data';
const emit = defineEmits<{ select: [categoryId?: StandardId] }>();
const access = useAccessStore();
const categories = ref<StandardCategory[]>([]);
const selected = ref<string[]>(['all']);
const expanded = ref<string[]>(['all']);
const search = ref('');
const loading = ref(false);
const failed = ref(false);
const tree = computed(() =>
  standardCategoryTree(categories.value, search.value),
);
const { allExpanded, toggleExpand } = useTreeExpansion(
  tree,
  expanded,
  search,
);
const requests = useObjectRequest(
  () => access.accessCodes.join(','),
  () => {
    categories.value = [];
    selected.value = ['all'];
    loading.value = false;
  },
);
async function load() {
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getStandardElementCategories();
    if (current()) {
      categories.value = result;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function select(keys: (number | string)[]) {
  const key = String(keys[0] ?? 'all');
  selected.value = [key];
  emit('select', key === 'all' ? undefined : key);
}
function reset() {
  search.value = '';
  selected.value = ['all'];
  expanded.value = ['all'];
}
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes],
  load,
  { deep: true },
);
onMounted(load);
defineExpose({ reset, reload: load });
</script>
<template>
  <div data-testid="standard-element-category-tree">
    <div class="standard-tree-header flex items-center gap-2">
      <Input
        class="min-w-0 flex-1"
        v-model:value="search"
        placeholder="请输入标准数据元类目"
        allow-clear
        aria-label="搜索标准数据元类目"
      >
        <template #prefix><IconifyIcon icon="lucide:search" /></template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="load"
      />
    </div>
    <Spin :spinning="loading">
      <template v-if="failed"></template>
      <Tree
        v-else
        v-model:expanded-keys="expanded"
        :selected-keys="selected"
        :tree-data="tree"
        block-node
        @select="select"
      >
        <template #titleRender="node">
          <span class="standard-tree-title" :title="String(node.title ?? '')"><img
              src="/images/rule-catalog-reference/tree-folder.png"
              alt=""
            /><span>{{ node.title }}</span></span>
        </template>
      </Tree>
    </Spin>
  </div>
</template>
