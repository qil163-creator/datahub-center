<script lang="ts" setup>
import type { TreeProps } from 'antdv-next';

import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';

import { computed, onMounted, ref } from 'vue';

import { Card, Input, Spin, Tree } from 'antdv-next';

import { getMetaObjectTree } from '#/api/governance/metaobject';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../common/use-tree-expansion';
import { metadataConnectionIcon } from '../metadata-common/icons';
import MetadataIcon from '../metadata-common/metadata-icon.vue';

/**
 * 元数据目录树：来源系统 → 数据源 → 库。
 * 定版元数据 / 最新元数据两页共用；选中「库」节点时抛出 select 事件。
 */
defineOptions({ name: 'GovernanceMetaObjectTree' });

const emit = defineEmits<{
  (e: 'select', node?: GovernanceMetaObjectApi.TreeNode): void;
  (e: 'loaded', nodes: GovernanceMetaObjectApi.TreeNode[]): void;
}>();

const treeLoading = ref(false);
const rawNodes = ref<GovernanceMetaObjectApi.TreeNode[]>([]);
const keyword = ref('');
const selectedKeys = ref<string[]>([]);
const expandedKeys = ref<string[]>([]);
const loadError = ref(false);
const request = useObjectRequest(
  () => '',
  () => {
    rawNodes.value = [];
    selectedKeys.value = [];
    emit('select', undefined);
  },
);
interface DisplayNode {
  title?: string;
  key: string;
  raw: GovernanceMetaObjectApi.TreeNode;
  children: DisplayNode[];
}

function nodeIcon(node?: GovernanceMetaObjectApi.TreeNode) {
  if (node?.type === 'system') return 'folder';
  if (node?.type === 'datasource') return metadataConnectionIcon(node.dbType);
  return 'database';
}

function buildTreeNodes(
  nodes: GovernanceMetaObjectApi.TreeNode[] | undefined,
  text: string,
): DisplayNode[] {
  const result: DisplayNode[] = [];
  for (const node of nodes ?? []) {
    const matched =
      !text || (node.name ?? '').toLowerCase().includes(text.toLowerCase());
    const children = buildTreeNodes(node.children, matched ? '' : text);
    if (!matched && children.length === 0) {
      continue;
    }
    result.push({
      title: node.name,
      key: node.key ?? `${node.type}:${node.id}`,
      raw: node,
      children,
    });
  }
  return result;
}

const treeData = computed(() =>
  buildTreeNodes(rawNodes.value, keyword.value.trim()),
);

const { allExpanded, toggleExpand } = useTreeExpansion(
  treeData,
  expandedKeys,
  keyword,
);

async function loadTree() {
  const current = request.begin();
  loadError.value = false;
  treeLoading.value = true;
  try {
    const result = await getMetaObjectTree();
    if (current()) {
      rawNodes.value = result;
      emit('loaded', result);
    }
  } catch {
    if (current()) {
      rawNodes.value = [];
      loadError.value = true;
    }
  } finally {
    if (current()) treeLoading.value = false;
  }
}

const handleSelect: NonNullable<TreeProps['onSelect']> = (keys, info) => {
  const raw = (
    info.node as unknown as { raw?: GovernanceMetaObjectApi.TreeNode }
  ).raw;
  if (keys.length > 0 && raw) {
    emit('select', raw);
  } else {
    selectedKeys.value = [];
    emit('select', undefined);
  }
};

defineExpose({ reload: loadTree });

onMounted(loadTree);
</script>

<template>
  <Card
    class="meta-tree-panel h-full shrink-0"
    :styles="{
      body: {
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'visible',
        padding: '10px',
      },
    }"
  >
    <div class="flex h-full min-h-0 flex-col overflow-hidden">
      <div class="metadata-tree-search flex items-center gap-2">
        <Input
          v-model:value="keyword"
          allow-clear
          class="min-w-0 flex-1"
          placeholder="请输入来源系统/数据源名称/库名"
        >
          <template #prefix>
            <MetadataIcon name="search" :size="14" />
          </template>
        </Input>
        <TreeToolbar
          :expanded="allExpanded"
          :loading="treeLoading"
          @toggle-expand="toggleExpand"
          @refresh="loadTree"
        />
      </div>
      <div class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
        <Spin :spinning="treeLoading">
          <Tree
            v-if="treeData.length > 0"
            v-model:expanded-keys="expandedKeys"
            v-model:selected-keys="selectedKeys"
            :tree-data="treeData"
            block-node
            class="pt-2"
            @select="handleSelect"
          >
            <template #titleRender="node">
              <span class="meta-tree-panel__node">
                <MetadataIcon :name="nodeIcon(node.raw)" :size="16" />
                <span
                  class="meta-tree-panel__node-name"
                  :title="
                    node.raw?.type === 'db'
                      ? `${node.title}（${node.raw?.tableCount ?? 0} 张表）`
                      : String(node.title ?? '')
                  "
                  >{{ node.title }}</span>
                <MetadataIcon
                  v-if="node.raw?.objectStatus === 2"
                  class="size-3.5 shrink-0 text-amber-500"
                  name="warning"
                  title="源端未发现，待确认删除"
                />
              </span>
            </template>
          </Tree>
          <div
            v-if="!treeLoading && treeData.length === 0"
            class="py-4 text-center text-xs text-gray-500"
          >
            {{
              loadError
                ? '加载失败，请点击刷新重试'
                : keyword
                  ? '没有匹配的来源'
                  : '暂无元数据，请先配置并执行采集任务'
            }}
          </div>
        </Spin>
      </div>
    </div>
  </Card>
</template>

<style scoped>
.meta-tree-panel {
  width: 280px;
  border: 0;
  border-radius: var(--radius);
}

.metadata-tree-search {
  position: relative;
  flex-shrink: 0;
  margin-bottom: 12px;
}

@media (max-width: 900px) {
  .meta-tree-panel {
    width: 100%;
    max-height: 230px;
  }
}

.meta-tree-panel :deep(.ant-tree-treenode) {
  width: 100%;
}

.meta-tree-panel :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.meta-tree-panel :deep(.ant-tree-title) {
  display: block;
  width: 100%;
  font-size: 14px;
}

.meta-tree-panel__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.meta-tree-panel__node-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
