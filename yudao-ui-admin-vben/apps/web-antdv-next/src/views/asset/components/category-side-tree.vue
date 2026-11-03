<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  Menu,
  Spin,
  Tooltip,
  Tree,
} from 'antdv-next';

import TreeToolbar from '#/components/tree-toolbar/index.vue';

/**
 * 资产模块通用左侧类目树。
 * 样式对齐数据服务「API 管理」：Card + w-1/6 + 搜索 / 展开 / 新建。
 */
interface CategoryItem {
  id: number | string;
  parentId?: number | string;
  categoryName?: string;
  status?: number;
  [key: string]: any;
}

interface TreeNode {
  key: string;
  title: string;
  count: number;
  disabled?: boolean;
  children?: TreeNode[];
}

const props = defineProps<{
  /** 类目权限前缀（如 asset:category-tag），传入后启用树上类目管理 */
  authPrefix?: string;
  countField?: string;
  items: CategoryItem[];
  loading?: boolean;
  rootTitle: string;
  tip?: string;
}>();

const emit = defineEmits<{
  (e: 'create' | 'edit', id: number | string): void;
  (e: 'refresh'): void;
  (e: 'delete', payload: { id: number | string; title: string }): void;
}>();

const { hasAccessByCodes } = useAccess();

const canCreate = computed(
  () => !!props.authPrefix && hasAccessByCodes([`${props.authPrefix}:create`]),
);
const canUpdate = computed(
  () => !!props.authPrefix && hasAccessByCodes([`${props.authPrefix}:update`]),
);
const canDelete = computed(
  () => !!props.authPrefix && hasAccessByCodes([`${props.authPrefix}:delete`]),
);
const manageable = computed(
  () => canCreate.value || canUpdate.value || canDelete.value,
);

const selectedId = defineModel<number | string | undefined>('selected');

const keyword = ref('');

const treeData = computed<TreeNode[]>(() => {
  const field = props.countField ?? 'count';
  const nodeMap = new Map<string, TreeNode>();
  props.items.forEach((item) => {
    const key = String(item.id);
    nodeMap.set(key, {
      key,
      title: item.categoryName ?? `类目#${item.id}`,
      count: Number(item[field] ?? 0),
      disabled: item.status === 1,
      children: [],
    });
  });
  const roots: TreeNode[] = [];
  props.items.forEach((item) => {
    const node = nodeMap.get(String(item.id))!;
    const parentKey =
      item.parentId !== null &&
      item.parentId !== undefined &&
      String(item.parentId) !== '0'
        ? String(item.parentId)
        : undefined;
    const parent = parentKey ? nodeMap.get(parentKey) : undefined;
    if (parent) {
      parent.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const text = keyword.value.trim();
  const filter = (nodes: TreeNode[]): TreeNode[] =>
    nodes
      .map((node) => ({ ...node, children: filter(node.children ?? []) }))
      .filter(
        (node) =>
          !text ||
          node.title.includes(text) ||
          (node.children?.length ?? 0) > 0,
      );
  return [
    {
      key: '0',
      title: props.rootTitle,
      count: props.items.reduce(
        (acc, item) => acc + Number(item[field] ?? 0),
        0,
      ),
      children: filter(roots),
    },
  ];
});

const expandedKeys = ref<string[]>(['0']);

function collectExpandableKeys(nodes: TreeNode[]): string[] {
  const keys: string[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      keys.push(node.key, ...collectExpandableKeys(node.children));
    }
  }
  return keys;
}

const isExpanded = computed(() => {
  const keys = collectExpandableKeys(treeData.value);
  return keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key));
});

function toggleExpandAll() {
  expandedKeys.value = isExpanded.value
    ? []
    : collectExpandableKeys(treeData.value);
}

watch(
  () => props.items,
  () => {
    expandedKeys.value = collectExpandableKeys(treeData.value);
  },
  { immediate: true },
);

function onSelect(keys: (number | string)[]) {
  const key = keys.length > 0 ? String(keys[0]) : '0';
  selectedId.value = key === '0' ? undefined : key;
}
</script>

<template>
  <Card
    class="category-side h-full w-1/6 shrink-0"
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
      <div class="mb-2 flex shrink-0 items-center gap-2">
        <Input
          v-model:value="keyword"
          allow-clear
          class="min-w-0 flex-1"
          placeholder="请输入类目名称"
        >
          <template #prefix>
            <IconifyIcon class="size-4" icon="lucide:search" />
          </template>
        </Input>
        <TreeToolbar
          :expanded="isExpanded"
          :loading="loading"
          @toggle-expand="toggleExpandAll"
          @refresh="emit('refresh')"
        />
        <Tooltip v-if="canCreate" title="新建根类目">
          <Button @click="emit('create', 0)">
            <template #icon>
              <IconifyIcon icon="lucide:plus" />
            </template>
          </Button>
        </Tooltip>
      </div>
      <Spin
        :spinning="loading"
        class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
      >
        <Tree
          v-model:expanded-keys="expandedKeys"
          :selected-keys="[selectedId ?? 0]"
          :tree-data="treeData"
          block-node
          class="pt-2"
          @select="onSelect"
        >
          <template #titleRender="node">
            <span class="category-side__node">
              <IconifyIcon
                class="size-4 shrink-0 text-blue-500"
                :icon="
                  node.key === '0' || node.children?.length
                    ? expandedKeys.includes(String(node.key))
                      ? 'lucide:folder-open'
                      : 'lucide:folder'
                    : 'lucide:file-text'
                "
              />
              <span class="category-side__name">{{ node.title }}</span>
              <span class="category-side__count">{{ node.count }}</span>
              <Dropdown
                v-if="node.key !== '0' && manageable"
                :trigger="['hover']"
                placement="bottomRight"
              >
                <span class="category-side__more" @click.stop>
                  <IconifyIcon icon="lucide:ellipsis" />
                </span>
                <template #popupRender>
                  <Menu>
                    <Menu.Item
                      v-if="canCreate"
                      key="add"
                      @click="emit('create', node.key)"
                    >
                      <span class="category-side__menu-item">
                        <IconifyIcon icon="lucide:plus" />
                        新增子类目
                      </span>
                    </Menu.Item>
                    <Menu.Item
                      v-if="canUpdate"
                      key="edit"
                      @click="emit('edit', node.key)"
                    >
                      <span class="category-side__menu-item">
                        <IconifyIcon icon="lucide:edit" />
                        编辑
                      </span>
                    </Menu.Item>
                    <Menu.Item
                      v-if="canDelete"
                      key="delete"
                      danger
                      @click="
                        emit('delete', {
                          id: node.key,
                          title: String(node.title ?? ''),
                        })
                      "
                    >
                      <span class="category-side__menu-item">
                        <IconifyIcon icon="lucide:trash-2" />
                        删除
                      </span>
                    </Menu.Item>
                  </Menu>
                </template>
              </Dropdown>
            </span>
          </template>
        </Tree>
        <Empty
          v-if="!loading && items.length === 0"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          :description="canCreate ? '暂无类目，点击右上角 + 新建' : '暂无类目'"
        />
      </Spin>
      <div
        v-if="tip"
        class="text-muted-foreground mt-2 shrink-0 truncate text-xs"
      >
        {{ tip }}
      </div>
    </div>
  </Card>
</template>

<style scoped>
.category-side :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.category-side :deep(.ant-tree-treenode) {
  width: 100%;
}

.category-side :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.category-side :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.category-side__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.category-side__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-side__count {
  padding: 0 6px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--accent));
  border-radius: 10px;
}

.category-side__menu-item {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.category-side__more {
  display: inline-flex;
  visibility: hidden;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  color: hsl(var(--muted-foreground));
  border-radius: 4px;
}

.category-side__more:hover {
  color: hsl(var(--primary));
  background: hsl(var(--accent));
}

.category-side :deep(.ant-tree-treenode:hover) .category-side__more,
.category-side :deep(.ant-tree-node-selected) .category-side__more {
  visibility: visible;
}
</style>
