<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { handleTree } from '@vben/utils';

import {
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  Menu,
  Spin,
  Tree,
} from 'antdv-next';

import TreeToolbar from '#/components/tree-toolbar/index.vue';

import { collectExpandableKeys, isTreeExpanded } from '../../../common/tree-expansion';

interface CategoryNode {
  children?: CategoryNode[];
  id: string;
  key: string;
  parentId: string;
  title: string;
}

defineOptions({ name: 'DataforgeFillPlanCategoryTree' });

const props = defineProps<{
  items: DataforgeFillApi.PlanCategory[];
  loading?: boolean;
}>();
const emit = defineEmits<{
  create: [parentId: number | string];
  delete: [category: { id: number | string; name: string }];
  edit: [id: number | string];
  refresh: [];
}>();
const selected = defineModel<number | string | undefined>('selected');
const { hasAccessByCodes } = useAccess();
const canCreate = computed(() =>
  hasAccessByCodes(['dataforge:fill-plan:create']),
);
const canUpdate = computed(() =>
  hasAccessByCodes(['dataforge:fill-plan:update']),
);
const canDelete = computed(() =>
  hasAccessByCodes(['dataforge:fill-plan:delete']),
);
const manageable = computed(
  () => canCreate.value || canUpdate.value || canDelete.value,
);

const ALL_PLANS_KEY = '__all_plans__';
const keyword = ref('');
const expandedKeys = ref<string[]>([]);
const expanded = computed(() => isTreeExpanded(treeData.value, expandedKeys.value));
const selectedKeys = computed(() => [String(selected.value ?? ALL_PLANS_KEY)]);

const categoryTree = computed(() =>
  handleTree<CategoryNode>(
    props.items
      .toSorted((left, right) => left.sort - right.sort)
      .map((item) => ({
        id: String(item.id),
        key: String(item.id),
        parentId: String(item.parentId),
        title: item.name,
      })),
  ),
);

function filterNodes(nodes: CategoryNode[], text: string): CategoryNode[] {
  return nodes.flatMap((node) => {
    if (!text || node.title.toLocaleLowerCase().includes(text)) return [node];
    const children = filterNodes(node.children ?? [], text);
    return children.length > 0 ? [{ ...node, children }] : [];
  });
}

const filteredCategories = computed(() =>
  filterNodes(categoryTree.value, keyword.value.trim().toLocaleLowerCase()),
);
const treeData = computed<CategoryNode[]>(() => [
  {
    id: ALL_PLANS_KEY,
    key: ALL_PLANS_KEY,
    parentId: '',
    title: '全部计划',
    children: [
      { id: '0', key: '0', parentId: ALL_PLANS_KEY, title: '未分类' },
      ...filteredCategories.value,
    ],
  },
]);

function toggleExpandAll() {
  expandedKeys.value = expanded.value ? [] : collectExpandableKeys(treeData.value);
}

watch(treeData, (nodes, previous) => {
  if (!previous || isTreeExpanded(previous, expandedKeys.value)) {
    expandedKeys.value = collectExpandableKeys(nodes);
  }
}, { immediate: true });
watch(keyword, (value) => {
  if (value.trim()) expandedKeys.value = collectExpandableKeys(treeData.value);
});

function onSelect(keys: (number | string)[]) {
  const key = keys[0] === undefined ? ALL_PLANS_KEY : String(keys[0]);
  selected.value = key === ALL_PLANS_KEY ? undefined : key;
}

function isCategory(key: number | string) {
  return String(key) !== ALL_PLANS_KEY && String(key) !== '0';
}
</script>

<template>
  <Card
    class="plan-category-tree w-full shrink-0 md:h-full md:w-[280px]"
    :styles="{
      body: {
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'hidden',
        padding: '12px',
      },
    }"
  >
    <div class="flex h-full min-h-0 flex-col overflow-hidden">
      <div class="mb-3 flex shrink-0 items-center justify-between">
        <span class="font-medium">计划分类</span>
      </div>
      <div class="mb-2 flex shrink-0 items-center gap-2">
        <Input
          v-model:value="keyword"
          allow-clear
          class="min-w-0 flex-1"
          placeholder="搜索分类"
          aria-label="搜索计划分类"
        >
          <template #prefix>
            <IconifyIcon class="size-4" icon="lucide:search" />
          </template>
        </Input>
        <TreeToolbar
          :expanded="expanded"
          :loading="loading"
          refresh-label="刷新分类"
          @toggle-expand="toggleExpandAll"
          @refresh="emit('refresh')"
        />
      </div>
      <Spin
        :spinning="loading"
        class="plan-category-tree__content subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
      >
        <Tree
          v-model:expanded-keys="expandedKeys"
          :selected-keys="selectedKeys"
          :tree-data="treeData"
          :auto-expand-parent="false"
          block-node
          class="pt-2"
          @select="onSelect"
        >
          <template #titleRender="node">
            <span class="plan-category-tree__node">
              <IconifyIcon
                class="size-4 shrink-0 text-blue-500"
                :icon="
                  node.key === ALL_PLANS_KEY || node.children?.length
                    ? expandedKeys.includes(String(node.key))
                      ? 'lucide:folder-open'
                      : 'lucide:folder'
                    : 'lucide:file-text'
                "
              />
              <span
                class="plan-category-tree__name"
                :title="String(node.title ?? '')"
              >
                {{ node.title }}
              </span>
              <Dropdown
                v-if="isCategory(node.key) && manageable"
                :trigger="['click']"
                placement="bottomRight"
              >
                <Button
                  class="plan-category-tree__more"
                  :aria-label="`管理分类 ${node.title}`"
                  type="text"
                  size="small"
                  :disabled="loading"
                  @click.stop
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:ellipsis" />
                  </template>
                </Button>
                <template #popupRender>
                  <Menu>
                    <Menu.Item
                      v-if="canCreate"
                      key="add"
                      @click="emit('create', String(node.key))"
                      ><template #icon><IconifyIcon icon="lucide:folder-plus" /></template>
                      新增子分类
                    </Menu.Item>
                    <Menu.Item
                      v-if="canUpdate"
                      key="edit"
                      @click="emit('edit', String(node.key))"
                      ><template #icon><IconifyIcon icon="lucide:edit" /></template>
                      编辑分类
                    </Menu.Item>
                    <Menu.Item
                      v-if="canDelete"
                      key="delete"
                      danger
                      @click="
                        emit('delete', {
                          id: String(node.key),
                          name: String(node.title ?? ''),
                        })
                      "
                      ><template #icon><IconifyIcon icon="lucide:trash-2" /></template>
                      删除分类
                    </Menu.Item>
                  </Menu>
                </template>
              </Dropdown>
            </span>
          </template>
        </Tree>
        <Empty
          v-if="!loading && filteredCategories.length === 0"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          :description="keyword.trim() ? '未找到匹配分类' : '暂无分类'"
        />
      </Spin>
    </div>
  </Card>
</template>

<style scoped>
.plan-category-tree :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.plan-category-tree :deep(.ant-tree-treenode) {
  width: 100%;
}

.plan-category-tree :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.plan-category-tree :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.plan-category-tree__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.plan-category-tree__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-category-tree__more {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  opacity: 0;
}

.plan-category-tree :deep(.ant-tree-treenode:hover) .plan-category-tree__more,
.plan-category-tree :deep(.ant-tree-node-selected) .plan-category-tree__more,
.plan-category-tree__more:focus-visible {
  opacity: 1;
}

@media (max-width: 767px) {
  .plan-category-tree__content {
    max-height: 240px;
  }
}
</style>
