<script lang="ts" setup>
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Button,
  Dropdown,
  Input,
  Menu,
  message,
  Modal,
  Spin,
  Tooltip,
  Tree,
} from 'antdv-next';

import {
  DataQueryApi,
  deleteDataQueryFile,
  getDataQueryFileList,
} from '#/api/asset/dataquery';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import FileForm from './file-form.vue';

defineOptions({ name: 'AssetDataQueryFileTree' });

const props = defineProps<{
  defaultDatasourceId?: number;
  defaultLimit?: number;
  defaultSql?: string;
  selectedId?: number;
}>();

const emit = defineEmits<{
  deleted: [id: number];
  open: [file: DataQueryApi.QueryFile];
  saved: [file: DataQueryApi.QueryFile];
}>();

const { hasAccessByCodes } = useAccess();
const canSave = computed(() => hasAccessByCodes(['asset:data-query:save']));
const canDelete = computed(() => hasAccessByCodes(['asset:data-query:delete']));

const items = ref<DataQueryApi.QueryFile[]>([]);
const loading = ref(false);
const keyword = ref('');
const expandedKeys = ref<number[]>([]);
const expandAll = ref(true);
const isExpanded = computed(() => {
  const keys = collectExpandableIds(fileTree.value);
  return keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key));
});
const formRef = ref<InstanceType<typeof FileForm>>();

const fileTree = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    return handleTree(items.value);
  }
  const byId = new Map(items.value.map((item) => [item.id, item]));
  const keepIds = new Set<number>();
  for (const item of items.value) {
    if (!item.name?.toLowerCase().includes(text)) {
      continue;
    }
    let current: DataQueryApi.QueryFile | undefined = item;
    while (
      current?.id !== undefined &&
      current.id !== null &&
      !keepIds.has(current.id)
    ) {
      keepIds.add(current.id);
      current = current.parentId ? byId.get(current.parentId) : undefined;
    }
  }
  return handleTree(items.value.filter((item) => keepIds.has(item.id!)));
});

function collectExpandableIds(nodes: any[]): number[] {
  const ids: number[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      ids.push(node.id, ...collectExpandableIds(node.children));
    }
  }
  return ids;
}

function applyExpandState() {
  expandedKeys.value = expandAll.value
    ? collectExpandableIds(fileTree.value)
    : [];
}

function toggleExpandAll() {
  expandAll.value = !isExpanded.value;
  applyExpandState();
}

function handleSearch() {
  if (keyword.value.trim()) {
    expandAll.value = true;
  }
  applyExpandState();
}

watch(fileTree, applyExpandState);

const active = ref(true);
const access = useAccessStore();
const user = useUserStore();
const request = useObjectRequest(
  () => JSON.stringify([active.value, access.accessCodes, user.userInfo?.userId, user.userRoles]),
  () => { items.value = []; loading.value = false; },
);
onDeactivated(() => { active.value = false; });
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void reload();
  }
});
async function reload() {
  if (!active.value) return;
  const current = request.begin();
  loading.value = true;
  try {
    const files = await getDataQueryFileList();
    if (current()) items.value = files;
  } finally {
    if (current()) loading.value = false;
  }
}

function asNode(node: unknown) {
  return node as DataQueryApi.QueryFile;
}

function isFolder(node: DataQueryApi.QueryFile) {
  return node.nodeType === DataQueryApi.FILE_NODE_FOLDER;
}

function isPrivate(node: DataQueryApi.QueryFile) {
  return node.visibility === DataQueryApi.FILE_VISIBILITY_PRIVATE;
}

function isOwned(node: DataQueryApi.QueryFile) {
  return !!node.owned;
}

function showNodeMenu(node: DataQueryApi.QueryFile) {
  if (isFolder(node) && canSave.value) {
    return true;
  }
  return isOwned(node) && (canSave.value || canDelete.value);
}

function handleSelect(keys: (number | string)[]) {
  if (keys.length === 0) {
    return;
  }
  const node = items.value.find((item) => String(item.id) === String(keys[0]));
  if (node && !isFolder(node)) {
    emit('open', node);
  }
}

function handleCreateFolder(parentId?: number) {
  formRef.value?.open({
    nodeType: DataQueryApi.FILE_NODE_FOLDER,
    parentId: parentId ?? 0,
    sort: 0,
  });
}

function handleCreateFile(parentId?: number) {
  formRef.value?.open({
    nodeType: DataQueryApi.FILE_NODE_FILE,
    parentId: parentId ?? 0,
    sort: 0,
    visibility: DataQueryApi.FILE_VISIBILITY_PRIVATE,
    sqlText: props.defaultSql,
    datasourceId: props.defaultDatasourceId,
    queryLimit: props.defaultLimit,
  });
}

function handleEdit(node: DataQueryApi.QueryFile) {
  formRef.value?.open({ ...node, children: undefined });
}

function handleSaveAs() {
  handleCreateFile();
}

function handleDelete(node: DataQueryApi.QueryFile) {
  Modal.confirm({
    title: '系统提示',
    content: isFolder(node)
      ? `确认删除目录「${node.name}」吗？目录下不能有子节点。`
      : `确认删除脚本「${node.name}」吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      await deleteDataQueryFile(node.id!);
      message.success('已删除');
      emit('deleted', node.id!);
      reload();
    },
  });
}

function handleFormSaved(file: DataQueryApi.QueryFile) {
  reload();
  emit('saved', file);
}

defineExpose({
  reload,
  openSaveAs: handleSaveAs,
  openCreateFile: handleCreateFile,
});

onMounted(reload);
</script>

<template>
  <div class="query-file-tree flex h-full min-h-0 flex-col overflow-hidden">
    <div class="mb-2 flex shrink-0 items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="min-w-0 flex-1"
        placeholder="请输入目录或脚本名称"
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
      <Tooltip title="新建根目录">
        <Button
          v-access:code="'asset:data-query:save'"
          @click="handleCreateFolder()"
        >
          <template #icon>
            <IconifyIcon icon="lucide:plus" />
          </template>
        </Button>
      </Tooltip>
    </div>
    <div class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <Spin :spinning="loading">
        <Tree
          v-if="fileTree.length > 0"
          v-model:expanded-keys="expandedKeys"
          :field-names="{ children: 'children', title: 'name', key: 'id' }"
          :selected-keys="selectedId != null ? [selectedId] : []"
          :tree-data="fileTree as any"
          block-node
          class="pt-2"
          @select="handleSelect"
          @expand="expandAll = isExpanded"
        >
          <template #titleRender="node">
            <span class="query-file-tree__node">
              <IconifyIcon
                class="size-4 shrink-0 text-blue-500"
                :icon="
                  isFolder(asNode(node)) ? 'lucide:folder' : 'lucide:file-code'
                "
              />
              <span class="query-file-tree__name">{{ asNode(node).name }}</span>
              <Tooltip v-if="isPrivate(asNode(node))" title="私有，仅自己可见">
                <IconifyIcon
                  class="text-muted-foreground size-3.5 shrink-0"
                  icon="lucide:lock"
                />
              </Tooltip>
              <Dropdown
                v-if="showNodeMenu(asNode(node))"
                :trigger="['hover']"
                placement="bottomRight"
              >
                <span class="query-file-tree__more" @click.stop>
                  <IconifyIcon icon="lucide:ellipsis" />
                </span>
                <template #popupRender>
                  <Menu>
                    <Menu.Item
                      v-if="canSave && isFolder(asNode(node))"
                      key="add-folder"
                      @click="handleCreateFolder(asNode(node).id)"
                    >
                      <template #icon><IconifyIcon icon="lucide:folder-plus" /></template>
                      新建子目录
                    </Menu.Item>
                    <Menu.Item
                      v-if="canSave && isFolder(asNode(node))"
                      key="add-file"
                      @click="handleCreateFile(asNode(node).id)"
                    >
                      <template #icon><IconifyIcon icon="lucide:file-plus" /></template>
                      新建脚本
                    </Menu.Item>
                    <Menu.Item
                      v-if="canSave && isOwned(asNode(node))"
                      key="edit"
                      @click="handleEdit(asNode(node))"
                    >
                      <template #icon><IconifyIcon icon="lucide:square-pen" /></template>
                      编辑
                    </Menu.Item>
                    <Menu.Item
                      v-if="canDelete && isOwned(asNode(node))"
                      key="delete"
                      danger
                      @click="handleDelete(asNode(node))"
                    >
                      <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
                      删除
                    </Menu.Item>
                  </Menu>
                </template>
              </Dropdown>
            </span>
          </template>
        </Tree>
        <div
          v-else-if="!loading"
          class="text-muted-foreground py-4 text-center text-xs"
        >
          暂无文件目录，点击右上角 + 新建
        </div>
      </Spin>
    </div>
    <FileForm ref="formRef" @saved="handleFormSaved" />
  </div>
</template>

<style scoped>
.query-file-tree :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.query-file-tree :deep(.ant-tree-treenode) {
  width: 100%;
}

.query-file-tree :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.query-file-tree :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.query-file-tree__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.query-file-tree__node :deep(.ant-dropdown-trigger) {
  margin-left: auto;
}

.query-file-tree__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-file-tree__more {
  display: inline-flex;
  visibility: hidden;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: auto;
  color: hsl(var(--muted-foreground));
  border-radius: 4px;
}

.query-file-tree__more:hover {
  color: hsl(var(--primary));
  background: hsl(var(--accent));
}

.query-file-tree :deep(.ant-tree-treenode:hover) .query-file-tree__more,
.query-file-tree :deep(.ant-tree-node-selected) .query-file-tree__more {
  visibility: visible;
}
</style>
