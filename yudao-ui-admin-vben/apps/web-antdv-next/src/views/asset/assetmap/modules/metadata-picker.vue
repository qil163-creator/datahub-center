<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetRegistrationApi } from '#/api/asset/dataasset/registration';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Empty,
  Input,
  message,
  Spin,
  Table,
  Tag,
  Tree,
} from 'antdv-next';

import { getAssetRegistrationCandidates } from '#/api/asset/dataasset/registration';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';

defineOptions({ name: 'AssetMetadataPicker' });
const emit = defineEmits<{
  (e: 'confirm', rows: AssetRegistrationApi.Candidate[]): void;
}>();
type Candidate = AssetRegistrationApi.Candidate;
interface SourceNode {
  key: string;
  title: string;
  count: number;
  children?: SourceNode[];
  match?: (row: Candidate) => boolean;
}
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:data-asset:create']),
);
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const assetType = ref(1);
const loading = ref(false);
const errorText = ref('');
const tooManyResults = ref(false);
const keyword = ref('');
const treeKeyword = ref('');
const allRows = ref<Candidate[]>([]);
const excludedIds = ref(new Set<string>());
const selectedRowKeys = ref<string[]>([]);
const selectedNodeKey = ref('all');
const pagination = reactive({ pageNo: 1, pageSize: 10 });
const guard = createLatestWins();
let open = false;
let alive = true;
function blocked(row: Candidate) {
  return !!row.registered || excludedIds.value.has(resourceIdKey(row.id));
}
async function loadAll() {
  if (!open || !alive || !canRead.value) return;
  const ticket = guard.next();
  loading.value = true;
  errorText.value = '';
  tooManyResults.value = false;
  allRows.value = [];
  selectedRowKeys.value = [];
  try {
    const rows: Candidate[] = [];
    let pageNo = 1;
    let total = 0;
    do {
      const result = await getAssetRegistrationCandidates({
        assetType: assetType.value,
        pageNo,
        pageSize: 200,
        keyword: keyword.value.trim() || undefined,
      });
      if (!ticket.isCurrent()) return;
      total = result.total ?? 0;
      if (total > 5000) {
        tooManyResults.value = true;
        errorText.value = '匹配结果较多，请输入关键字缩小查询范围。';
        return;
      }
      if (!result.list?.length && rows.length < total)
        throw new Error('INCOMPLETE_CANDIDATE_PAGE');
      rows.push(...(result.list ?? []));
      pageNo += 1;
    } while (rows.length < total);
    if (ticket.isCurrent()) {
      allRows.value = rows;
      pagination.pageNo = 1;
    }
  } catch {
    if (ticket.isCurrent()) errorText.value = '元数据读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function sourceName(row: Candidate) {
  return row.sourceSystemName || '未分类';
}
function databaseName(row: Candidate) {
  return (
    row.dbName || (row.datasourceId ? `数据源 #${row.datasourceId}` : '全部')
  );
}
const sourceTree = computed<SourceNode[]>(() => {
  const systems = new Map<string, Map<string, Candidate[]>>();
  for (const row of allRows.value) {
    const system = sourceName(row);
    const database = databaseName(row);
    if (!systems.has(system)) systems.set(system, new Map());
    const databases = systems.get(system)!;
    if (!databases.has(database)) databases.set(database, []);
    databases.get(database)!.push(row);
  }
  const nodes: SourceNode[] = [];
  systems.forEach((databases, system) => {
    const children: SourceNode[] = [];
    let count = 0;
    databases.forEach((rows, database) => {
      count += rows.length;
      children.push({
        key: `db:${system}:${database}`,
        title: database,
        count: rows.length,
        match: (row) =>
          sourceName(row) === system && databaseName(row) === database,
      });
    });
    nodes.push({
      key: `sys:${system}`,
      title: system,
      count,
      children,
      match: (row) => sourceName(row) === system,
    });
  });
  const text = treeKeyword.value.trim();
  const filtered = text
    ? nodes
        .map((node) => ({
          ...node,
          children: node.children?.filter(
            (child) => node.title.includes(text) || child.title.includes(text),
          ),
        }))
        .filter((node) => node.title.includes(text) || !!node.children?.length)
    : nodes;
  return filtered;
});
function findNode(nodes: SourceNode[], key: string): SourceNode | undefined {
  for (const node of nodes) {
    if (node.key === key) return node;
    const child = node.children && findNode(node.children, key);
    if (child) return child;
  }
}
const visibleRows = computed(() => {
  const node = findNode(sourceTree.value, selectedNodeKey.value);
  return allRows.value.filter((row) => !node?.match || node.match(row));
});
const columns = [
  { title: '编号', key: 'index', width: 180, align: 'center' as const },
  { title: '来源信息', key: 'name' },
  {
    title: '注册状态',
    key: 'registered',
    width: 180,
    align: 'center' as const,
  },
];
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  preserveSelectedRowKeys: true,
  getCheckboxProps: (row: Candidate) => ({ disabled: blocked(row) }),
  onChange: (keys: (number | string)[]) => {
    selectedRowKeys.value = keys
      .map(String)
      .filter((key) =>
        allRows.value.some(
          (row) => resourceIdKey(row.id) === key && !blocked(row),
        ),
      );
  },
}));
function reset() {
  guard.invalidate();
  allRows.value = [];
  selectedRowKeys.value = [];
  loading.value = false;
  errorText.value = '';
  tooManyResults.value = false;
}
const [Modal, modalApi] = useVbenModal({
  class: 'w-[80vw]',
  fullscreenButton: false,
  confirmText: '确定',
  async onConfirm() {
    if (!open || !canRead.value || loading.value) return;
    const rows = allRows.value.filter(
      (row) =>
        selectedRowKeys.value.includes(resourceIdKey(row.id)) && !blocked(row),
    );
    if (rows.length === 0) {
      message.warning('请至少选择一项元数据');
      return;
    }
    emit('confirm', rows);
    await modalApi.close();
  },
  async onOpenChange(isOpen: boolean) {
    open = isOpen;
    reset();
    if (!isOpen) return;
    const data = modalApi.getData() as
      | undefined
      | {
          assetType?: number;
          excludedIds?: DataAssetApi.Id[];
        };
    assetType.value =
      data?.assetType === 2 || data?.assetType === 3 ? data.assetType : 1;
    excludedIds.value = new Set(
      (data?.excludedIds ?? []).map((id) => resourceIdKey(id)),
    );
    selectedNodeKey.value = 'all';
    keyword.value = '';
    treeKeyword.value = '';
    await loadAll();
  },
});
watch(
  identity,
  () => {
    reset();
    if (open) void modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  alive = false;
  open = false;
  reset();
});
const folderIcon = '/assets/qdata-market/icon-folder.png';
function iconPath(name: string) {
  return `/assets/qdata-map/icon-${name}.svg`;
}
</script>

<template>
  <Modal
    title="选择元数据"
    class="metadata-picker-modal !top-[6vh] !w-[80vw] !max-w-[calc(100vw-32px)] !max-h-[calc(94dvh-16px)] !rounded-[var(--radius)]"
    content-class="!p-0"
    :confirm-disabled="loading || !!errorText || !canRead"
  >
    <Spin :spinning="loading">
      <div class="picker-wrap">
        <div class="picker-search">
          <Input
            v-model:value="keyword"
            allow-clear
            :placeholder="
              assetType === 3
                ? '请输入文件名搜索'
                : assetType === 2
                  ? '请输入接口名称搜索'
                  : '请输入表名搜索'
            "
            @press-enter="loadAll"
            @change="loadAll"
          >
            <template #prefix>
              <img :src="iconPath('search')" alt="" />
            </template>
          </Input>
        </div>
        <div class="picker">
          <aside class="picker__aside">
            <h3>来源系统架构</h3>
            <div class="picker__tree">
              <Input
                v-model:value="treeKeyword"
                allow-clear
                placeholder="请输入来源系统/数据源名称"
              >
                <template #prefix>
                  <img :src="iconPath('search')" alt="" />
                </template>
              </Input>
              <Tree
                :default-expand-all="true"
                :selected-keys="[selectedNodeKey]"
                :tree-data="sourceTree"
                block-node
                @select="
                  (keys) => {
                    selectedNodeKey = String(keys[0] || 'all');
                    pagination.pageNo = 1;
                  }
                "
              >
                <template #titleRender="node">
                  <span class="picker__node"><img :src="folderIcon" alt="" /><span>{{
                      node.title
                    }}</span></span>
                </template>
              </Tree>
            </div>
          </aside>
          <main class="picker__main">
            <h3>
              {{
                assetType === 3
                  ? '文件列表'
                  : assetType === 2
                    ? 'API列表'
                    : '表列表'
              }}
            </h3>
            <div class="picker__table">
              <Alert v-if="tooManyResults" message="匹配结果较多，请输入关键字缩小查询范围。" type="warning" />
              <Table
                :columns="columns"
                :data-source="visibleRows"
                :pagination="{
                  current: pagination.pageNo,
                  pageSize: pagination.pageSize,
                  showSizeChanger: false,
                  showTotal: (total: number) => `共 ${total} 条`,
                  onChange: (pageNo: number, pageSize: number) => {
                    pagination.pageNo = pageNo;
                    pagination.pageSize = pageSize;
                  },
                }"
                :row-selection="rowSelection"
                :scroll="{ y: 'calc(80vh - 190px)' }"
                :row-key="(row: Candidate) => resourceIdKey(row.id)"
                size="small"
              >
                <template #bodyCell="{ column, record, index }">
                  <span v-if="column.key === 'index'">{{
                    (pagination.pageNo - 1) * pagination.pageSize + index + 1
                  }}</span>
                  <div
                    v-else-if="column.key === 'name'"
                    :title="record.path || record.dbName"
                  >
                    <span>{{
                      record.fileName || record.name || record.tableName || '-'
                    }}</span><Tag v-if="assetType !== 1" class="picker__filetype">
                      {{
                        assetType === 2
                          ? record.method || 'API'
                          : record.fileType || '-'
                      }}
</Tag><small v-if="assetType === 1">{{
                      record.businessName ||
                      record.customComment ||
                      record.sourceComment ||
                      '-'
                    }}</small>
                  </div>
                  <span
                    v-else-if="column.key === 'registered'"
                    class="picker__status"
                    :class="[{ 'picker__status--registered': blocked(record) }]"
                    >{{
                      record.registered
                        ? '已注册'
                        : excludedIds.has(resourceIdKey(record.id))
                          ? '已选入'
                          : '未注册'
                    }}</span>
                </template>
                <template #emptyText>
                  <Empty description="暂无记录" />
                </template>
              </Table>
            </div>
          </main>
        </div>
      </div>
    </Spin>
    <template #footer>
      <Button :disabled="loading" @click="modalApi.onCancel()">取消</Button>
      <Button
        type="primary"
        :disabled="loading || !canRead"
        @click="modalApi.onConfirm()"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>
<style scoped>
.picker-wrap {
  padding: 15px 25px;
  font-size: 14px;
  color: hsl(var(--foreground));
}

.picker-search {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}

.picker-search :deep(.ant-input-affix-wrapper) {
  width: 320px;
}

.picker-wrap :deep(img) {
  width: 16px;
  height: 16px;
}

.picker {
  display: flex;
  height: calc(80vh - 75px);
  min-height: 360px;
  border: 1px solid #e5e7eb;
}

.picker h3 {
  height: 32px;
  padding: 6px 20px;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #7e8997;
  background: hsl(var(--muted));
}

.picker__aside {
  flex: 0 0 350px;
  min-width: 0;
  overflow: auto;
  border-right: 1px solid #e5e7eb;
}

.picker__tree {
  padding: 10px;
}

.picker__tree :deep(.ant-input-affix-wrapper) {
  margin-bottom: 14px;
}

.picker__node {
  display: flex;
  gap: 6px;
  align-items: center;
}

.picker__main {
  flex: 1;
  min-width: 0;
}

.picker__table {
  padding: 10px;
}

.picker__table small {
  display: block;
  color: hsl(var(--muted-foreground));
}

.picker__filetype {
  padding: 0 6px;
  margin-left: 8px;
  font-size: 12px;
  line-height: 20px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border: 0;
}

.picker__status {
  padding: 3px 9px;
  color: #8e44ad;
  background: #efedf7;
}

.picker__status--registered {
  color: #00b31b;
  background: #e5f6e8;
}

@media (width <= 1100px) {
  .picker__aside {
    flex-basis: 240px;
  }
}
</style>

<style scoped src="../../table-font.css"></style>
