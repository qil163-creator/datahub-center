<script setup lang="ts">
import type { TreeProps } from 'antdv-next';

import type { MetricFilter, MetricTreeNode } from '../state';

import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Input, Spin, Tree } from 'antdv-next';

import TreeToolbar from '#/components/tree-toolbar/index.vue';

import treeDocument from '../reference-assets/tree-document.png';
import treeFolderOpen from '../reference-assets/tree-folder-open.png';
import treeFolder from '../reference-assets/tree-folder.png';
import { buildMetricCategoryTree } from '../state';

/** 类目只使用平台真实的数据域、主题域与模型归属，不构造业务分类记录。 */
const props = withDefaults(
  defineProps<{
    domains?: ModelingDataDomainApi.DataDomain[];
    kind?: 'atomic' | 'condition' | 'derived';
    loading?: boolean;
    models?: {
      code?: string;
      dataDomainId?: IndicatorModelApi.Id;
      id: IndicatorModelApi.Id;
      name?: string;
      subjectDomainId?: IndicatorModelApi.Id;
    }[];
    subjects?: ModelingSubjectDomainApi.SubjectDomain[];
    value?: {
      dataDomainId?: IndicatorModelApi.Id;
      sourceModelId?: IndicatorModelApi.Id;
      subjectDomainId?: IndicatorModelApi.Id;
    };
  }>(),
  {
    domains: () => [],
    subjects: () => [],
    models: () => [],
    kind: 'atomic',
    loading: false,
    value: () => ({}),
  },
);
const emit = defineEmits<{
  refresh: [];
  select: [value: NonNullable<typeof props.value>];
  'update:value': [value: NonNullable<typeof props.value>];
}>();
const keyword = ref('');
const selected = ref('all');
const names = {
  atomic: '原子指标类目',
  condition: '业务限定类目',
  derived: '派生指标类目',
};
const nodes = computed(() =>
  buildMetricCategoryTree(
    props.domains,
    props.subjects,
    props.models,
    names[props.kind],
    keyword.value,
  ),
);
const expandedKeys = ref<(number | string)[]>(['all']);
const expandableKeys = computed(() =>
  nodes.value.flatMap((node) => [
    node.key,
    ...node.children.flatMap((child) => [
      child.key,
      ...child.children
        .filter((item) => item.children.length > 0)
        .map((item) => item.key),
    ]),
  ]),
);
const isExpanded = computed(() =>
  expandableKeys.value.length > 0 &&
  expandableKeys.value.every((key) => expandedKeys.value.includes(key)),
);
function toggleExpandAll() {
  expandedKeys.value = isExpanded.value ? [] : [...expandableKeys.value];
}
watch(
  () => `${keyword.value}/${expandableKeys.value.join('/')}`,
  () => {
    expandedKeys.value = keyword.value.trim()
      ? [...expandableKeys.value]
      : expandedKeys.value.filter((key) =>
          expandableKeys.value.includes(String(key)),
        );
  },
  { immediate: true },
);
watch(
  () => props.value,
  (value) => {
    selected.value = 'all';
    if (value.sourceModelId !== undefined) {
      selected.value = `model:${value.sourceModelId}`;
      return;
    }
    if (value.dataDomainId === undefined) return;
    if (value.subjectDomainId !== undefined) {
      selected.value = `subject:${value.dataDomainId}:${value.subjectDomainId}`;
      return;
    }
    selected.value = `domain:${value.dataDomainId}`;
  },
  { immediate: true, deep: true },
);
function findFilter(
  items: MetricTreeNode[],
  key: string,
): MetricFilter | undefined {
  for (const item of items) {
    if (item.key === key) return item.filter;
    const child = findFilter(item.children, key);
    if (child) return child;
  }
}
const select: NonNullable<TreeProps['onSelect']> = (keys) => {
  selected.value = String(keys[0] ?? 'all');
  const filter = findFilter(nodes.value, selected.value) ?? {};
  emit('update:value', filter);
  emit('select', filter);
};
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-hidden">
    <div class="mb-2 flex shrink-0 items-center gap-2">
      <Input
      v-model:value="keyword"
      class="min-w-0 flex-1"
      allow-clear
      placeholder="请输入业务板块/主题域/逻辑表名"
      aria-label="搜索指标类目"
    >
      <template #prefix>
        <IconifyIcon class="size-4" icon="lucide:search" />
      </template>
      </Input>
      <TreeToolbar
        compact
        :expanded="isExpanded"
        :loading="loading"
        @toggle-expand="toggleExpandAll"
        @refresh="emit('refresh')"
      />
    </div>
    <div class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <Spin :spinning="loading">
        <Tree
          :tree-data="nodes"
          v-model:expanded-keys="expandedKeys"
          :selected-keys="[selected]"
          block-node
          @select="select"
        >
          <template #titleRender="node">
            <span class="metric-tree__title">
              <img
                class="metric-tree__icon"
                :src="
                  (node.children?.length ?? 0) > 0 || node.level === 'all'
                    ? expandedKeys.includes(node.key)
                      ? treeFolderOpen
                      : treeFolder
                    : treeDocument
                "
                alt=""
              />
              <span class="truncate">{{ node.title }}</span>
            </span>
          </template>
        </Tree>
      </Spin>
    </div>
  </div>
</template>

<style scoped>
.metric-tree__title {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  font-size: 14px;
  line-height: 22px;
}

.metric-tree__icon {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  object-fit: contain;
}

:deep(.ant-tree) {
  color: #606266;
  background: transparent;
}

:deep(.ant-tree-treenode) {
  min-height: 22px;
  padding-bottom: 0;
}

:deep(.ant-tree-node-content-wrapper) {
  min-height: 22px;
  padding: 0 4px;
  line-height: 22px;
}

:deep(.ant-tree-switcher) {
  width: 16px;
  height: 22px;
  line-height: 22px;
}

:deep(.ant-tree-node-selected) {
  color: #4564ff;
  background: #f0f7ff;
}

:deep(.ant-tree-indent-unit) {
  width: 16px;
}

:deep(.ant-input-affix-wrapper) {
  height: 28px;
  padding: 2px 8px;
  border-color: #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

:deep(.ant-input) {
  font-size: 14px;
}
</style>
