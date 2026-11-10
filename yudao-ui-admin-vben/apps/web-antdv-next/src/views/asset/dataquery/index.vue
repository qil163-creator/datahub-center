<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  message,
  Segmented,
  Select,
  Spin,
  Tooltip,
  Tree,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  DataQueryApi,
  executeDataQuery,
  getDataQueryColumnList,
  getDataQueryDatasourceList,
  getDataQueryFile,
  getDataQueryTableList,
  updateDataQueryFile,
} from '#/api/asset/dataquery';
import { ConnectionIcon } from '#/components/connection-icon';
import {
  resolveSqlLanguage,
  setColumnLoader,
  setCompletionTables,
} from '#/components/monaco/setup';
import SqlEditor from '#/components/monaco/sql-editor.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { formatQueryCell } from '../market/map/data';
import FileTree from './modules/file-tree.vue';

defineOptions({ name: 'AssetDataQuery' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const active = ref(true);
let alive = true;
const owner = computed(() =>
  JSON.stringify([
    Boolean(access.accessToken),
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const requestScope = computed(() => `${owner.value}/${active.value}`);
const canQuery = computed(
  () =>
    active.value &&
    Boolean(access.accessToken) &&
    !access.loginExpired &&
    hasAccessByCodes(['asset:data-query:query']),
);
const canExecute = computed(
  () =>
    active.value &&
    Boolean(access.accessToken) &&
    !access.loginExpired &&
    hasAccessByCodes(['asset:data-query:execute']),
);
const datasourceRequest = useObjectRequest(() => requestScope.value);
const fileRequest = useObjectRequest(() => requestScope.value);
const saveRequest = useObjectRequest(() => requestScope.value);

/** ==================== 左侧：数据源 → 表 → 字段 树 ==================== */

interface TreeNode {
  key: string;
  title: string;
  isLeaf?: boolean;
  selectable?: boolean;
  children?: TreeNode[];
  /** 节点业务信息 */
  nodeType: 'column' | 'datasource' | 'table';
  datasourceId: number;
  tableName?: string;
  columnName?: string;
  typeName?: string;
  /** 数据源节点的连接图标路径 */
  iconPath?: string;
}

const leftMode = ref<'datasource' | 'file'>('datasource');
const leftModeOptions = [
  { label: '数据源', value: 'datasource' },
  { label: '文件目录', value: 'file' },
];

const datasources = ref<DataQueryApi.Datasource[]>([]);
const treeData = ref<TreeNode[]>([]);
const treeLoading = ref(false);
const treeKeyword = ref('');
const expandedKeys = ref<string[]>([]);
const loadedKeys = ref<string[]>([]);
const expandingTree = ref(false);
let treeRevision = 0;
let expansionRevision = 0;
const pendingNodeLoads = new Map<string, Promise<void>>();
function invalidateTreeLoads() {
  treeRevision++;
  expansionRevision++;
  expandingTree.value = false;
  pendingNodeLoads.clear();
}
function branchKeys(nodes: TreeNode[]): string[] {
  const keys: string[] = [];
  const pending = [...nodes];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const node = pending.pop()!;
    if (visited.has(node.key)) continue;
    visited.add(node.key);
    if (!node.isLeaf) keys.push(node.key);
    pending.push(...(node.children ?? []));
  }
  return keys;
}
const isTreeExpanded = computed(() => {
  const keys = branchKeys(filteredTreeData.value);
  return (
    keys.length > 0 &&
    keys.every(
      (key) =>
        expandedKeys.value.includes(key) && loadedKeys.value.includes(key),
    )
  );
});
async function toggleTreeExpanded() {
  if (expandingTree.value || isTreeExpanded.value) {
    invalidateTreeLoads();
    expandedKeys.value = [];
    return;
  }
  if (treeLoading.value) return;
  const expansion = ++expansionRevision;
  const revision = treeRevision;
  const current = () =>
    alive &&
    canQuery.value &&
    expansion === expansionRevision &&
    revision === treeRevision;
  expandingTree.value = true;
  const pending = [...filteredTreeData.value];
  const visited = new Set<string>();
  try {
    while (pending.length > 0 && current()) {
      const node = pending.shift()!;
      if (node.isLeaf || visited.has(node.key)) continue;
      visited.add(node.key);
      await onLoadData(node);
      if (!current()) return;
      if (!expandedKeys.value.includes(node.key)) {
        expandedKeys.value = [...expandedKeys.value, node.key];
      }
      pending.push(...(node.children ?? []));
    }
  } catch {
    // 读取失败由统一请求客户端提示，停止本轮展开并允许重试。
  } finally {
    if (expansion === expansionRevision) expandingTree.value = false;
  }
}
watch(treeKeyword, invalidateTreeLoads);
const fileTreeRef = ref<InstanceType<typeof FileTree>>();
const currentFile = ref<DataQueryApi.QueryFile>();
const savingFile = ref(false);

const filteredTreeData = computed(() => {
  const keyword = treeKeyword.value.trim().toLowerCase();
  if (!keyword) return treeData.value;
  return treeData.value.filter((node) =>
    node.title.toLowerCase().includes(keyword),
  );
});

async function loadDatasources() {
  if (!alive || !canQuery.value) return;
  const current = datasourceRequest.begin();
  invalidateTreeLoads();
  treeLoading.value = true;
  try {
    const rows = await getDataQueryDatasourceList();
    if (!current()) return;
    invalidateTreeLoads();
    datasources.value = rows;
    expandedKeys.value = [];
    loadedKeys.value = [];
    treeData.value = rows.map((item) => ({
      key: `ds:${item.id}`,
      title: item.name,
      nodeType: 'datasource',
      datasourceId: item.id,
      iconPath: item.iconPath,
      isLeaf: false,
    }));
  } finally {
    if (current()) treeLoading.value = false;
  }
}

/** 树懒加载：数据源 → 表清单，表 → 字段清单。刷新和收起会废弃旧结果。 */
async function onLoadData(node: any): Promise<void> {
  if (!alive || !canQuery.value) return;
  if (treeLoading.value)
    throw new DOMException('Tree refreshing', 'AbortError');
  const data = (node.dataRef ?? node) as TreeNode;
  if (data.isLeaf || data.children !== undefined) return;
  const pending = pendingNodeLoads.get(data.key);
  if (pending) return pending;
  const revision = treeRevision;
  const context = owner.value;
  const token = access.accessToken;
  const refreshToken = access.refreshToken;
  const current = () =>
    alive &&
    canQuery.value &&
    revision === treeRevision &&
    context === owner.value &&
    token === access.accessToken &&
    refreshToken === access.refreshToken;
  const request = (async () => {
    let children: TreeNode[];
    if (data.nodeType === 'datasource') {
      const tables = await getDataQueryTableList(data.datasourceId);
      children = tables.map((tableName) => ({
        key: `table:${data.datasourceId}:${tableName}`,
        title: tableName,
        nodeType: 'table',
        datasourceId: data.datasourceId,
        tableName,
        isLeaf: false,
      }));
    } else if (data.nodeType === 'table') {
      const columns = await getDataQueryColumnList(
        data.datasourceId,
        data.tableName!,
      );
      children = columns.map((column) => ({
        key: `col:${data.datasourceId}:${data.tableName}:${column.columnName}`,
        title: column.typeName
          ? `${column.columnName}（${column.typeName}）`
          : column.columnName,
        nodeType: 'column',
        datasourceId: data.datasourceId,
        tableName: data.tableName,
        columnName: column.columnName,
        typeName: column.typeName,
        isLeaf: true,
      }));
    } else return;
    if (!current())
      throw new DOMException('Tree loading cancelled', 'AbortError');
    data.children = children;
    data.isLeaf = children.length === 0;
    loadedKeys.value = [...new Set([...loadedKeys.value, data.key])];
    treeData.value = [...treeData.value];
  })();
  pendingNodeLoads.set(data.key, request);
  try {
    await request;
  } finally {
    if (pendingNodeLoads.get(data.key) === request)
      pendingNodeLoads.delete(data.key);
  }
}

/** 点击数据源：同步右侧下拉；点击表 / 字段：同步数据源并把名称插入编辑器 */
function onTreeSelect(_keys: (number | string)[], info: any) {
  const data = (info?.node?.dataRef ?? info?.node) as TreeNode | undefined;
  if (!data) return;
  if (datasourceId.value !== data.datasourceId) {
    onDatasourceChange(data.datasourceId);
  }
  if (data.nodeType === 'datasource') return;
  const inserted =
    data.nodeType === 'table' ? data.tableName! : data.columnName!;
  const current = sql.value ?? '';
  const suffix = current === '' || current.endsWith(' ') ? '' : ' ';
  sql.value = current + suffix + inserted;
}

const treeSelectedKeys = computed(() =>
  datasourceId.value === undefined || datasourceId.value === null
    ? []
    : [`ds:${datasourceId.value}`],
);

/** ==================== 右侧：SQL 编辑器与执行 ==================== */

const datasourceId = ref<number>();
const sql = ref('');
const limit = ref(100);
const executing = ref(false);
const executed = ref(false);
const error = ref('');
const result = ref<DataQueryApi.Result>({});
const editorRef = ref<InstanceType<typeof SqlEditor>>();
const completionRequest = useObjectRequest(
  () => `${requestScope.value}/${datasourceId.value}`,
);

const datasourceOptions = computed(() =>
  datasources.value.map((item) => ({
    label: item.productType ? `${item.name}（${item.productType}）` : item.name,
    value: item.id,
  })),
);

const editorLanguage = computed(() => {
  const datasource = datasources.value.find(
    (item) => item.id === datasourceId.value,
  );
  return resolveSqlLanguage(datasource?.productType);
});

/** 切换数据源：展开对应树节点，并为编辑器注册表/字段补全 */
async function onDatasourceChange(id?: number) {
  if (datasourceId.value !== id) invalidateTreeLoads();
  datasourceId.value = id;
  const current = completionRequest.begin();
  setCompletionTables([]);
  setColumnLoader(null);
  if (!id || !alive || !canQuery.value) return;
  if (!expandedKeys.value.includes(`ds:${id}`)) {
    expandedKeys.value = [...expandedKeys.value, `ds:${id}`];
  }
  try {
    const tables = await getDataQueryTableList(id);
    if (!current()) return;
    setCompletionTables(tables.map((name) => ({ name })));
    setColumnLoader(async (tableName) => {
      if (!current()) return [];
      const columns = await getDataQueryColumnList(id, tableName);
      if (!current()) return [];
      return columns.map((column) => ({
        name: column.columnName,
        dataType: column.typeName,
      }));
    });
  } catch {
    if (current()) {
      setCompletionTables([]);
      setColumnLoader(null);
    }
  }
}

function buildResultGrid(next: DataQueryApi.Result) {
  const sourceColumns = next.columns ?? [];
  const columns: VxeTableGridOptions['columns'] = [
    { type: 'seq', width: 56, title: '#' },
    ...sourceColumns.map((column, index) => ({
      field: `__c${index}`,
      title: column.typeName
        ? `${column.label}（${column.typeName}）`
        : column.label || column.name,
      minWidth: 140,
      formatter: ({ cellValue }: { cellValue: unknown }) =>
        formatQueryCell(cellValue, column.typeName),
    })),
  ];
  const rows = (next.rows ?? []).map((row, rowIndex) => {
    const mapped: Record<string, unknown> = { __rowKey: rowIndex };
    sourceColumns.forEach((column, index) => {
      mapped[`__c${index}`] = row[column.name];
    });
    return mapped;
  });
  return { columns, rows };
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: [{ type: 'seq', width: 56, title: '#' }],
    data: [],
    emptyText: '选择数据源并输入 SELECT 后点击「查询」',
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: true,
      pageSize: 20,
    },
    proxyConfig: {
      enabled: false,
    },
    rowConfig: { keyField: '__rowKey', isHover: true },
    toolbarConfig: {
      custom: true,
      refresh: false,
      search: false,
    },
  } as VxeTableGridOptions<Record<string, any>>,
});

let resultRevision = 0;
function syncResultGrid(next: DataQueryApi.Result, emptyText: string) {
  const revision = ++resultRevision;
  const { columns, rows } = buildResultGrid(next);
  result.value = { ...next, rows };
  gridApi.setGridOptions({ columns, data: rows, emptyText });
  nextTick(() => {
    if (!alive || revision !== resultRevision) return;
    gridApi.grid?.reloadColumn?.(columns);
    gridApi.grid?.reloadData?.(rows);
  });
}

const queryRequest = useObjectRequest(
  () =>
    JSON.stringify([
      requestScope.value,
      datasourceId.value,
      sql.value,
      limit.value,
    ]),
  () => {
    executing.value = false;
    executed.value = false;
    error.value = '';
    gridApi.setLoading(false);
    syncResultGrid({}, '选择数据源并输入 SELECT 后点击「查询」');
  },
);

watch(
  () => [owner.value, access.accessToken, access.refreshToken] as const,
  ([context], [previousContext]) => {
    datasourceRequest.begin();
    completionRequest.begin();
    fileRequest.begin();
    saveRequest.begin();
    invalidateTreeLoads();
    loadedKeys.value = [];
    datasources.value = [];
    treeData.value = [];
    // 同用户正常刷新令牌只重载请求上下文，未保存草稿仍由当前用户持有。
    if (context !== previousContext) {
      expandedKeys.value = [];
      currentFile.value = undefined;
      datasourceId.value = undefined;
      sql.value = '';
    }
    treeLoading.value = false;
    savingFile.value = false;
    setCompletionTables([]);
    setColumnLoader(null);
    void loadDatasources();
    if (context === previousContext)
      void onDatasourceChange(datasourceId.value);
  },
  { flush: 'sync' },
);

/** 执行查询：优先执行编辑器中选中的语句 */
async function handleExecute() {
  if (!alive || !canExecute.value) return;
  if (!datasourceId.value) {
    message.warning('请先选择数据源');
    return;
  }
  const statement = (
    editorRef.value?.getSelectionOrValue() ?? sql.value
  ).trim();
  if (!statement) {
    message.warning('请输入查询 SQL');
    return;
  }
  const current = queryRequest.begin();
  const request = {
    datasourceId: datasourceId.value,
    sql: statement,
    limit: limit.value,
  };
  executing.value = true;
  error.value = '';
  gridApi.setLoading(true);
  try {
    const next = await executeDataQuery(request);
    if (!current()) return;
    executed.value = true;
    syncResultGrid(next, '查询成功，无数据');
  } catch (error_: any) {
    if (!current()) return;
    error.value = error_?.message || '查询失败';
    executed.value = true;
    syncResultGrid({}, '查询失败');
  } finally {
    if (current()) {
      executing.value = false;
      gridApi.setLoading(false);
    }
  }
}

function handleClear() {
  fileRequest.begin();
  sql.value = '';
  currentFile.value = undefined;
  editorRef.value?.focus();
}

async function handleOpenFile(node: DataQueryApi.QueryFile) {
  if (!node.id || !alive || !canQuery.value) return;
  const current = fileRequest.begin();
  const detail = await getDataQueryFile(node.id);
  if (!current()) return;
  currentFile.value = detail;
  sql.value = detail.sqlText ?? '';
  if (detail.queryLimit) {
    limit.value = detail.queryLimit;
  }
  leftMode.value = 'file';
  await onDatasourceChange(detail.datasourceId);
}

async function handleSaveFile() {
  if (
    !alive ||
    savingFile.value ||
    !active.value ||
    !hasAccessByCodes(['asset:data-query:save'])
  )
    return;
  const statement = sql.value ?? '';
  if (!statement.trim() && !currentFile.value) {
    message.warning('请先输入要保存的 SQL');
    return;
  }
  if (!currentFile.value?.id || currentFile.value.owned === false) {
    leftMode.value = 'file';
    await nextTick();
    fileTreeRef.value?.openSaveAs();
    return;
  }
  const current = saveRequest.begin();
  const file = currentFile.value;
  const request = {
    ...file,
    sqlText: statement,
    datasourceId: datasourceId.value,
    queryLimit: limit.value,
  };
  savingFile.value = true;
  try {
    await updateDataQueryFile(request);
    if (!current()) return;
    if (currentFile.value === file) currentFile.value = request;
    message.success('脚本已保存');
    fileTreeRef.value?.reload();
  } finally {
    if (current()) savingFile.value = false;
  }
}

async function handleSaveFileAs() {
  leftMode.value = 'file';
  await nextTick();
  fileTreeRef.value?.openSaveAs();
}

async function handleFileSaved(file: DataQueryApi.QueryFile) {
  if (file.nodeType !== DataQueryApi.FILE_NODE_FILE || !file.id) {
    return;
  }
  if (currentFile.value?.id === file.id) {
    currentFile.value = { ...currentFile.value, ...file };
    return;
  }
  await handleOpenFile(file);
}

function handleFileDeleted(id: number) {
  if (currentFile.value?.id === id) {
    currentFile.value = undefined;
  }
}

onMounted(loadDatasources);
onActivated(() => {
  if (active.value) return;
  active.value = true;
  if (datasources.value.length === 0) void loadDatasources();
  void onDatasourceChange(datasourceId.value);
});
onDeactivated(() => {
  invalidateTreeLoads();
  active.value = false;
  treeLoading.value = false;
  savingFile.value = false;
  setCompletionTables([]);
  setColumnLoader(null);
});
onUnmounted(() => {
  invalidateTreeLoads();
  alive = false;
  resultRevision++;
});
</script>

<template>
  <Page auto-content-height content-class="flex flex-col overflow-hidden">
    <template #description>
      <p class="text-muted-foreground text-sm">
        对关系型数据库执行只读 SELECT，结果最多返回 1000 行；支持 Ctrl + Enter
        执行当前选中或全部语句。常用 SQL
        可保存到左侧「文件目录」，可选择公开或私有。
      </p>
    </template>
    <div class="flex min-h-0 flex-1 gap-3 overflow-hidden">
      <Card
        class="query-tree h-full w-1/6 shrink-0"
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
        <Segmented
          v-model:value="leftMode"
          block
          class="mb-2 shrink-0"
          :options="leftModeOptions"
        />
        <div
          v-show="leftMode === 'datasource'"
          class="flex min-h-0 flex-1 flex-col overflow-hidden"
        >
          <div class="mb-2 flex shrink-0 items-center gap-2">
            <Input
              v-model:value="treeKeyword"
              allow-clear
              class="min-w-0 flex-1"
              placeholder="请输入数据源名称"
            >
              <template #prefix>
                <IconifyIcon class="size-4" icon="lucide:search" />
              </template>
            </Input>
            <TreeToolbar
              :expanded="expandingTree || isTreeExpanded"
              :loading="treeLoading || expandingTree"
              refresh-label="刷新数据源"
              @toggle-expand="toggleTreeExpanded"
              @refresh="loadDatasources"
            />
          </div>
          <Spin
            :spinning="treeLoading"
            class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
          >
            <Tree
              v-model:expanded-keys="expandedKeys"
              :loaded-keys="loadedKeys"
              :load-data="onLoadData"
              :selected-keys="treeSelectedKeys"
              :tree-data="filteredTreeData as any"
              block-node
              @select="onTreeSelect"
            >
              <template #titleRender="node">
                <span class="flex min-w-0 items-center gap-1">
                  <ConnectionIcon
                    v-if="node.nodeType === 'datasource'"
                    :icon-path="node.iconPath"
                    alt=""
                    class="size-4 shrink-0 object-contain"
                  />
                  <IconifyIcon
                    v-else
                    :icon="
                      node.nodeType === 'table'
                        ? 'carbon:data-table'
                        : 'carbon:column'
                    "
                    class="shrink-0 text-blue-500"
                  />
                  <Tooltip :title="String(node.title ?? '')">
                    <span class="truncate">{{ node.title }}</span>
                  </Tooltip>
                </span>
              </template>
            </Tree>
            <Empty
              v-if="!treeLoading && filteredTreeData.length === 0"
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="暂无可查询的数据源"
            />
          </Spin>
        </div>
        <FileTree
          v-show="leftMode === 'file'"
          ref="fileTreeRef"
          class="min-h-0 flex-1"
          :default-datasource-id="datasourceId"
          :default-limit="limit"
          :default-sql="sql"
          :selected-id="currentFile?.id"
          @deleted="handleFileDeleted"
          @open="handleOpenFile"
          @saved="handleFileSaved"
        />
      </Card>

      <div class="flex min-h-0 min-w-0 flex-1 flex-col gap-3 overflow-hidden">
        <Card class="shrink-0" :styles="{ body: { padding: '12px 16px' } }">
          <div class="flex flex-wrap items-center gap-2">
            <span class="shrink-0 text-sm font-medium">数据查询</span>
            <span
              v-if="currentFile?.name"
              class="text-muted-foreground inline-flex max-w-[200px] items-center gap-1 truncate text-xs"
              :title="currentFile.name"
            >
              · {{ currentFile.name }}
              <span>
                （{{
                  currentFile.visibility ===
                  DataQueryApi.FILE_VISIBILITY_PRIVATE
                    ? '私有'
                    : '公开'
                }}）
              </span>
            </span>
            <Select
              :options="datasourceOptions"
              :value="datasourceId"
              allow-clear
              class="w-[280px]"
              placeholder="请选择数据源"
              show-search
              @change="onDatasourceChange($event as number | undefined)"
            />
            <span class="text-muted-foreground shrink-0 text-xs">最大行数</span>
            <InputNumber
              v-model:value="limit"
              :max="1000"
              :min="1"
              class="w-[90px]"
            />
            <div class="flex-1"></div>
            <Button :loading="executing" type="primary" @click="handleExecute">
              <IconifyIcon class="mr-1" icon="carbon:play" />
              查询
            </Button>
            <Button
              v-access:code="'asset:data-query:save'"
              :loading="savingFile"
              @click="handleSaveFile"
            >
              <template #icon><IconifyIcon icon="lucide:save" /></template>
              保存
            </Button>
            <Button
              v-access:code="'asset:data-query:save'"
              @click="handleSaveFileAs"
            >
              <template #icon><IconifyIcon icon="lucide:save-all" /></template>
              另存为
            </Button>
            <Button @click="editorRef?.format()">
              <template #icon><IconifyIcon icon="lucide:braces" /></template>
              格式化
            </Button>
            <Button danger @click="handleClear">
              <template #icon><IconifyIcon icon="lucide:eraser" /></template>
              清空
            </Button>
          </div>
        </Card>

        <Card class="query-pane query-editor" :bordered="true">
          <div class="query-editor__host">
            <SqlEditor
              ref="editorRef"
              v-model="sql"
              :language="editorLanguage"
              height="100%"
              @run="handleExecute"
            />
          </div>
        </Card>

        <div class="query-result">
          <Alert
            v-if="error"
            :message="error"
            class="mb-2 shrink-0"
            show-icon
            type="error"
          />
          <Grid class="query-result-grid min-h-0 flex-1" table-title="查询结果">
            <template #toolbar-tools>
              <span
                v-if="executed && !error"
                class="text-muted-foreground mr-2 text-xs"
              >
                共 {{ result.rows?.length ?? 0 }} 行
                <template v-if="result.truncated">
                  （已达最大返回行数，结果被截断）
                </template>
                <template v-if="result.elapsedMs != null">
                  · 耗时 {{ result.elapsedMs }} ms
                </template>
              </span>
            </template>
          </Grid>
        </div>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.query-result-grid :deep(.vxe-body--column) {
  font-size: 12px;
}

.query-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.query-tree :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.query-tree :deep(.ant-tree) {
  background: transparent;
}

.query-tree :deep(.ant-tree-treenode) {
  width: 100%;
}

.query-tree :deep(.query-file-tree) {
  flex: 1;
  min-height: 0;
}

.query-pane {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.query-editor {
  flex: 1.1;
}

.query-result {
  display: flex;
  flex: 1.4;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.query-pane :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 8px 12px;
}

.query-editor__host {
  position: relative;
  flex: 1;
  min-height: 0;
}

.query-editor__host :deep(> div) {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100% !important;
}
</style>
