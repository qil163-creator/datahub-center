<script setup lang="ts">
import type { SafetyCategoryWorkbenchApi as CategoryApi } from '#/api/governance/category/workbench';

import { computed, ref } from 'vue';

import {
  Alert,
  Button,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  Spin,
  Tree,
} from 'antdv-next';

import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { resourceIdKey } from '#/utils/resource-id';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import { categoryDirectoryTree } from '../reference-data';

const props = defineProps<{
  canCreate?: boolean;
  canDelete?: boolean;
  canEdit?: boolean;
  directories: CategoryApi.Directory[];
  error?: string;
  loading?: boolean;
  selectedId?: CategoryApi.Id;
}>();
const emit = defineEmits<{
  collapse: [];
  create: [parentId?: string];
  edit: [id: string];
  remove: [id: string];
  retry: [];
  select: [id?: string];
}>();
const keyword = ref('');
const searchIcon = '/assets/qdata-project-category/search.png';
const expanded = ref<string[]>([]);
const allKey = '__all__';
const nodes = computed(() => [
  {
    key: allKey,
    title: '数据分类类目',
    children: categoryDirectoryTree(props.directories, keyword.value),
  },
]);
const selected = computed(() => [resourceIdKey(props.selectedId) || allKey]);
const { allExpanded, toggleExpand } = useTreeExpansion(
  nodes,
  expanded,
  keyword,
);
function isFolder(node: unknown) {
  const value = node as {
    dataRef?: { key?: string };
    eventKey?: string;
    key?: string;
  };
  const key = value.key || value.eventKey || value.dataRef?.key;
  return (
    key === allKey ||
    props.directories.some((item) => resourceIdKey(item.parentId) === key)
  );
}
</script>

<template>
  <aside class="category-sidebar">
    <div class="flex items-center gap-2">
      <Input
        class="min-w-0 flex-1"
        v-model:value="keyword"
        placeholder="请输入数据分类类目"
        allow-clear
      >
        <template #prefix>
          <img :src="searchIcon" class="category-tree-search" alt="" />
        </template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="emit('retry')"
      />
    </div>
    <Alert v-if="error" type="error" :message="error">
      <template #action>
        <Button size="small" @click="emit('retry')">重试</Button>
      </template>
    </Alert>
    <div class="category-sidebar-scroll subtle-tree-scrollbar">
      <Spin :spinning="loading">
        <Tree
          v-model:expanded-keys="expanded"
          :selected-keys="selected"
          :tree-data="nodes"
          block-node
          @select="
            (keys) =>
              emit(
                'select',
                keys[0] && keys[0] !== allKey ? String(keys[0]) : undefined,
              )
          "
        >
          <template #titleRender="node">
            <span class="category-tree-title">
              <img
                :src="
                  isFolder(node)
                    ? '/assets/qdata-market/icon-folder.png'
                    : '/assets/qdata-market/icon-document.png'
                "
                class="category-tree-icon"
                alt=""
              />
              <span class="category-tree-label">{{ node.title }}</span>
              <Dropdown
                v-if="
                  canCreate || (node.key !== allKey && (canEdit || canDelete))
                "
                :trigger="['click']"
              >
                <button
                  class="category-tree-more"
                  type="button"
                  aria-label="类目操作"
                  @click.stop
                >
                  •••
</button><template #popupRender>
                  <Menu>
                    <MenuItem
                      v-if="canCreate"
                      key="child"
                      @click="
                        emit(
                          'create',
                          node.key === allKey ? '0' : String(node.key),
                        )
                      "
                    >
                      新增子级
</MenuItem><MenuItem
                      v-if="canCreate && node.key !== allKey"
                      key="sibling"
                      @click="
                        emit(
                          'create',
                          resourceIdKey(
                            directories.find(
                              (item) =>
                                resourceIdKey(item.id) === String(node.key),
                            )?.parentId,
                          ) || '0',
                        )
                      "
                    >
                      新增同级
</MenuItem><MenuItem
                      v-if="canEdit && node.key !== allKey"
                      key="edit"
                      @click="emit('edit', String(node.key))"
                    >
                      修改
</MenuItem><MenuItem
                      v-if="canDelete && node.key !== allKey"
                      key="delete"
                      @click="emit('remove', String(node.key))"
                    >
                      删除
                    </MenuItem>
                  </Menu>
                </template>
              </Dropdown>
            </span>
          </template>
        </Tree>
      </Spin>
    </div>
</aside>
</template>
