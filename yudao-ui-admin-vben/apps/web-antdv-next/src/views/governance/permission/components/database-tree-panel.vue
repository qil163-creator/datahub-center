<script lang="ts" setup>
import type { SafetyPermissionApi } from '#/api/governance/permission';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Input, message, Spin, Tag, Tooltip, Tree } from 'antdv-next';

import {
  getPermissionDatabaseList,
  getPermissionTableList,
} from '#/api/governance/permission';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

defineOptions({ name: 'SafetyPermissionDatabaseTree' });

const emit = defineEmits<{
  (
    e: 'tableSelect',
    table: SafetyPermissionApi.TableNode & {
      databaseId: number | string;
      databaseName?: string;
    },
  ): void;
}>();

interface TreeNode {
  key: string;
  title: string;
  isLeaf?: boolean;
  children?: TreeNode[];
  raw?:
    | SafetyPermissionApi.Database
    | (SafetyPermissionApi.TableNode & { databaseId: number | string });
  nodeType: 'database' | 'table';
  tableCount?: number;
  loadFailed?: boolean;
}

const loading = ref(false);
let requestSeq = 0;
onBeforeUnmount(() => {
  ++requestSeq;
});
const keyword = ref('');
const treeData = ref<TreeNode[]>([]);
const expandedKeys = ref<string[]>([]);
const selectedKeys = ref<string[]>([]);
const loadedTables = new Map<string, TreeNode[]>();
const loadingDatabases = new Map<string, Promise<void>>();

const filteredTreeData = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    return treeData.value;
  }
  return filterNodes(treeData.value, text);
});

function filterNodes(nodes: TreeNode[], text: string): TreeNode[] {
  const result: TreeNode[] = [];
  for (const node of nodes) {
    const children = node.children ? filterNodes(node.children, text) : [];
    const remark =
      node.raw && 'remarks' in node.raw
        ? String(node.raw.remarks ?? '').toLowerCase()
        : '';
    const matched =
      (node.title ?? '').toLowerCase().includes(text) || remark.includes(text);
    if (matched || children.length > 0) {
      result.push({ ...node, children });
    }
  }
  return result;
}

async function loadDatabases() {
  const seq = ++requestSeq;
  const selectedDatabaseKeys = new Set(
    treeData.value
      .filter((node) =>
        node.children?.some((child) => selectedKeys.value.includes(child.key)),
      )
      .map((node) => node.key),
  );
  const reloadKeys = new Set(
    treeData.value
      .filter(
        (node) =>
          expandedKeys.value.includes(node.key) ||
          node.children?.some((child) =>
            selectedKeys.value.includes(child.key),
          ),
      )
      .map((node) => node.key),
  );
  loading.value = true;
  loadedTables.clear();
  loadingDatabases.clear();
  try {
    const list = await getPermissionDatabaseList();
    if (seq !== requestSeq) return;
    treeData.value = (list ?? []).map((item) => ({
      key: `db-${String(item.id)}`,
      title: item.name || `数据源 ${item.id}`,
      nodeType: 'database',
      isLeaf: false,
      raw: item,
    }));
    expandedKeys.value = expandedKeys.value.filter((key) =>
      treeData.value.some((node) => node.key === key),
    );
    await Promise.all(
      treeData.value
        .filter((node) => keyword.value.trim() || reloadKeys.has(node.key))
        .map((node) =>
          ensureTablesLoaded(node.raw as SafetyPermissionApi.Database),
        ),
    );
    if (seq !== requestSeq) return;
    const validKeys = new Set(
      treeData.value.flatMap((node) => [
        node.key,
        ...(node.children ?? []).map((child) => child.key),
      ]),
    );
    selectedKeys.value = selectedKeys.value.filter(
      (key) =>
        validKeys.has(key) ||
        treeData.value.some(
          (node) => node.loadFailed && selectedDatabaseKeys.has(node.key),
        ),
    );
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

async function ensureTablesLoaded(database: SafetyPermissionApi.Database) {
  const id = String(database.id);
  if (loadedTables.has(id)) return;
  const pending = loadingDatabases.get(id);
  if (pending) return pending;
  const request = loadTables(database, requestSeq);
  loadingDatabases.set(id, request);
  return request;
}

async function loadTables(database: SafetyPermissionApi.Database, seq: number) {
  const id = String(database.id);
  try {
    const tables = await getPermissionTableList(database.id, {
      silentError: true,
    });
    if (seq !== requestSeq) return;
    if (
      !tables ||
      tables.some(
        (table) =>
          !table.schemaName || !table.tableName || !table.tableReference,
      )
    ) {
      throw new Error('精确表身份缺失');
    }
    const children = tables.map((table) => ({
      key: JSON.stringify(['table', id, table.schemaName, table.tableName]),
      title: table.tableReference,
      isLeaf: true,
      nodeType: 'table' as const,
      raw: { ...table, databaseId: database.id, databaseName: database.name },
    }));
    attachDatabaseChildren(id, children);
  } catch {
    if (seq !== requestSeq) return;
    const origin = treeData.value.find((item) => item.key === `db-${id}`);
    if (origin) {
      origin.loadFailed = true;
      treeData.value = [...treeData.value];
    }
    message.warning(
      `数据源「${database.name || database.id}」表清单加载失败，请稍后点击刷新重试`,
    );
  } finally {
    if (seq === requestSeq) loadingDatabases.delete(id);
  }
}

function attachDatabaseChildren(id: string, children: TreeNode[]) {
  loadedTables.set(id, children);
  const origin = treeData.value.find((item) => item.key === `db-${id}`);
  if (origin) {
    origin.loadFailed = false;
    origin.children = children;
    origin.tableCount = children.length;
    origin.isLeaf = children.length === 0;
  }
  treeData.value = [...treeData.value];
}

async function handleLoadData(node: any) {
  if (loading.value) {
    throw new DOMException('Database tree is refreshing', 'AbortError');
  }
  const data = (node.dataRef ?? node) as TreeNode;
  if (data.nodeType !== 'database' || !data.raw) {
    return;
  }
  await ensureTablesLoaded(data.raw as SafetyPermissionApi.Database);
}

watch(keyword, async (value) => {
  const text = value.trim();
  if (!text || loading.value) {
    return;
  }
  await Promise.all(
    treeData.value
      .filter((item) => item.nodeType === 'database' && item.raw)
      .map((item) =>
        ensureTablesLoaded(item.raw as SafetyPermissionApi.Database),
      ),
  );
});

function handleSelect(_keys: any, info: any) {
  const node = (info?.node?.dataRef ?? info?.node) as TreeNode | undefined;
  if (node?.nodeType === 'table' && node.raw) {
    selectedKeys.value = [node.key];
    emit(
      'tableSelect',
      node.raw as SafetyPermissionApi.TableNode & {
        databaseId: number | string;
        databaseName?: string;
      },
    );
  }
}

function collectExpandableKeys(nodes: TreeNode[]): string[] {
  const keys: string[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      keys.push(node.key, ...collectExpandableKeys(node.children));
    } else if (node.isLeaf === false) {
      keys.push(node.key);
    }
  }
  return keys;
}

const allExpanded = computed(() => {
  const keys = collectExpandableKeys(filteredTreeData.value);
  return (
    keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key))
  );
});

async function toggleExpand() {
  if (allExpanded.value) {
    expandedKeys.value = [];
    return;
  }
  if (loading.value) return;
  expandedKeys.value = collectExpandableKeys(filteredTreeData.value);
  await Promise.all(
    filteredTreeData.value
      .filter((node) => node.nodeType === 'database' && node.raw)
      .map((node) =>
        ensureTablesLoaded(node.raw as SafetyPermissionApi.Database),
      ),
  );
}

defineExpose({ reload: loadDatabases });

onMounted(loadDatabases);
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <div class="mb-3 flex shrink-0 items-center gap-2">
      <Input
        v-model:value="keyword"
        allow-clear
        class="min-w-0 flex-1"
        placeholder="搜索表名或数据源"
      >
        <template #prefix>
          <IconifyIcon class="size-4" icon="lucide:search" />
        </template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="loadDatabases"
      />
    </div>
    <div class="subtle-tree-scrollbar min-h-0 flex-1 overflow-auto">
      <Spin :spinning="loading">
        <Tree
          v-if="filteredTreeData.length > 0"
          class="permission-database-tree"
          v-model:expanded-keys="expandedKeys"
          :load-data="handleLoadData"
          :selected-keys="selectedKeys"
          :tree-data="filteredTreeData"
          block-node
          @select="handleSelect"
        >
          <template #titleRender="node">
            <span class="flex min-w-0 items-center gap-1.5">
              <IconifyIcon
                :icon="
                  node.nodeType === 'database'
                    ? 'lucide:database'
                    : 'carbon:data-table'
                "
                :class="
                  node.nodeType === 'database'
                    ? 'text-emerald-500'
                    : 'text-blue-500'
                "
                class="size-4 shrink-0"
              />
              <Tooltip
                :title="
                  node.raw && 'remarks' in node.raw && node.raw.remarks
                    ? `${node.title}（${node.raw.remarks}）`
                    : String(node.title ?? '')
                "
              >
                <span class="truncate">{{ node.title }}</span>
              </Tooltip>
              <Tag
                v-if="node.raw && 'viewTable' in node.raw && node.raw.viewTable"
                class="shrink-0"
                size="small"
              >
                视图
              </Tag>
              <Tag
                v-if="node.nodeType === 'database' && !node.loadFailed"
                class="ml-auto shrink-0"
                size="small"
              >
                {{
                  node.tableCount === undefined
                    ? '未加载'
                    : `${node.tableCount} 表`
                }}
              </Tag>
            </span>
          </template>
        </Tree>
        <div v-else class="py-10 text-center text-sm text-muted-foreground">
          暂无数据源
        </div>
      </Spin>
    </div>
  </div>
</template>

<style scoped>
.permission-database-tree :deep(.ant-tree-title) {
  font-size: 14px;
}
</style>
