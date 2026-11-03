<script setup lang="ts">
import type { GovernanceMetaFileApi as Api } from '#/api/governance/metafile';

import { computed, onMounted, ref } from 'vue';

import { Alert, Button, Input, Spin, Tree } from 'antdv-next';

import { getMetaFileTree } from '#/api/governance/metafile';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import { metadataConnectionIcon } from '../../metadata-common/icons';
import MetadataIcon from '../../metadata-common/metadata-icon.vue';

const emit = defineEmits<{ select: [node?: Api.TreeNode] }>();
const keyword = ref('');
const nodes = ref<Api.TreeNode[]>([]);
const expanded = ref<string[]>([]);
const selected = ref<string[]>([]);
const loading = ref(false);
const error = ref(false);
const requests = useObjectRequest(
  () => '',
  () => {
    nodes.value = [];
    selected.value = [];
    emit('select');
  },
);
function filter(list: Api.TreeNode[]): Api.TreeNode[] {
  const term = keyword.value.trim().toLocaleLowerCase();
  return list.flatMap((node) => {
    const children = filter(node.children ?? []);
    return !term ||
      node.name.toLocaleLowerCase().includes(term) ||
      children.length > 0
      ? [{ ...node, children }]
      : [];
  });
}
const tree = computed(() => filter(nodes.value));
const { allExpanded, toggleExpand } = useTreeExpansion(
  tree,
  expanded,
  keyword,
);
async function load() {
  const current = requests.begin();
  loading.value = true;
  error.value = false;
  try {
    const data = await getMetaFileTree();
    if (current()) {
      nodes.value = data;
    }
  } catch {
    if (current()) error.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function findNode(list: Api.TreeNode[], key: string): Api.TreeNode | undefined {
  for (const node of list) {
    if (node.id === key) return node;
    const child = findNode(node.children ?? [], key);
    if (child) return child;
  }
}
function select(keys: unknown[]) {
  const key = String(keys[0] ?? '');
  selected.value = key ? [key] : [];
  emit('select', key ? findNode(nodes.value, key) : undefined);
}
defineExpose({ reload: load });
onMounted(load);
</script>
<template>
  <aside class="metadata-file-tree subtle-tree-scrollbar">
    <div class="flex items-center gap-2">
      <Input
        class="min-w-0 flex-1"
        v-model:value="keyword"
        placeholder="请输入来源系统/数据源名称"
        allow-clear
      >
        <template #prefix><MetadataIcon name="search" /></template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="load"
      />
    </div>
    <Alert v-if="error" type="error" message="目录读取失败">
      <template #action>
        <Button size="small" @click="load">
          <template #icon>
            <MetadataIcon name="refresh" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <Spin :spinning="loading">
      <Tree
        v-model:expanded-keys="expanded"
        :selected-keys="selected"
        :tree-data="tree"
        :field-names="{ key: 'id', title: 'name', children: 'children' }"
        block-node
        @select="select"
      >
        <template #titleRender="node">
          <span class="metadata-file-tree__node"><MetadataIcon
              :name="
                node.type === 'connection'
                  ? metadataConnectionIcon(node.dbType)
                  : 'folder'
              "
            /><span :title="node.name">{{ node.name }}</span></span>
        </template>
      </Tree>
      <div
        v-if="!loading && !error && !nodes.length"
        class="py-6 text-center text-xs text-gray-400"
      >
        暂无文件元数据
      </div>
    </Spin>
  </aside>
</template>
<style scoped>
.metadata-file-tree {
  flex-shrink: 0;
  width: 280px;
  padding: 10px;
  overflow: auto;
  background: hsl(var(--card));
}

.metadata-file-tree :deep(.ant-tree) {
  margin-top: 12px;
  font-size: 14px;
}

.metadata-file-tree__node {
  display: flex;
  gap: 7px;
  align-items: center;
  min-width: 0;
}

.metadata-file-tree__node > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-file-tree__node :deep(svg) {
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .metadata-file-tree {
    width: 100%;
    max-height: 240px;
  }
}
</style>
