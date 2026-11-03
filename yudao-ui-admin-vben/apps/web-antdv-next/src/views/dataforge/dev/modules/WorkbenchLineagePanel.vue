<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import { Button, Input, Modal, Select } from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';

import { defaultExecutionConfig } from '../list-model';

interface DatabaseOption {
  label: string;
  productId?: string;
  value: DataforgeDevApi.Id;
}
interface LineageRow {
  databaseId?: DataforgeDevApi.Id;
  error?: string;
  key: string;
  loading: boolean;
  schemaName?: null | string;
  tableName: string;
  tables: string[];
}
type Direction = 'downstream' | 'upstream';
const props = withDefaults(
  defineProps<{
    databases?: DatabaseOption[];
    readonly?: boolean;
    saving?: boolean;
    task: DataforgeDevApi.Task;
    tasks?: DataforgeDevApi.Task[];
  }>(),
  { databases: () => [], tasks: () => [] },
);
const emit = defineEmits<{
  cancel: [];
  collapse: [];
  save: [
    patch: Pick<DataforgeDevApi.Task, 'dependencyTaskIds' | 'executionConfig'>,
  ];
}>();
const access = useAccessStore();
const panelAssets = {
  reparse: '/assets/qdata-development/panel-reparse.png',
  addNode: '/assets/qdata-development/panel-add-node.png',
  details: '/assets/qdata-development/panel-lineage-detail.png',
};
const direction = ref<Direction>('upstream');
const rows = ref<Record<Direction, LineageRow[]>>({
  downstream: [],
  upstream: [],
});
const dependencies = ref<DataforgeDevApi.Id[]>([]);
const parseMessage = ref('');
const error = ref('');
const detailsOpen = ref(false);
const locked = computed(() => props.readonly || props.task.status === 1);
const pending = computed(() =>
  [...rows.value.upstream, ...rows.value.downstream].some((row) => row.loading),
);
const complete = computed(() => ({
  upstream: rows.value.upstream.filter(
    (row) => row.databaseId !== undefined && row.tableName,
  ),
  downstream: rows.value.downstream.filter(
    (row) => row.databaseId !== undefined && row.tableName,
  ),
}));
const dependencyOptions = computed(() => {
  const options: { disabled?: boolean; label: string; value: string }[] =
    props.tasks
      .filter(
        (task) =>
          task.id !== undefined && String(task.id) !== String(props.task.id),
      )
      .map((task) => ({
        value: String(task.id),
        label: task.name || `任务 #${task.id}`,
      }));
  for (const id of dependencies.value)
    if (!options.some((option) => option.value === String(id)))
      options.push({ value: String(id), label: `任务 #${id}`, disabled: true });
  return options;
});
let epoch = 0;
let rowSequence = 0;
const tableCache = new Map<string, Promise<string[]>>();
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
function databaseName(id: DataforgeDevApi.Id | undefined) {
  return (
    props.databases.find((database) => String(database.value) === String(id))
      ?.label ?? (id === undefined ? '请选择数据连接' : `连接 #${id}`)
  );
}
function databaseOptions(row: LineageRow) {
  const options: { disabled?: boolean; label: string; value: string }[] =
    props.databases.map((database) => ({
      label: database.label,
      value: String(database.value),
    }));
  if (
    row.databaseId !== undefined &&
    !options.some((option) => option.value === String(row.databaseId))
  )
    options.push({
      value: String(row.databaseId),
      label: `不可用连接 #${row.databaseId}`,
      disabled: true,
    });
  return options;
}
function tableOptions(row: LineageRow) {
  const options: { disabled?: boolean; label: string; value: string }[] =
    row.tables.map((tableName) => ({ value: tableName, label: tableName }));
  if (
    row.tableName &&
    !options.some((option) => option.value === row.tableName)
  )
    options.push({
      value: row.tableName,
      label: row.tableName,
      disabled: !locked.value,
    });
  return options;
}
async function loadTables(row: LineageRow) {
  const current = epoch;
  const databaseId = row.databaseId;
  if (databaseId === undefined || locked.value) return;
  row.loading = true;
  row.error = undefined;
  row.tables = [];
  const key = String(databaseId);
  try {
    let request = tableCache.get(key);
    if (!request) {
      request = getDatabaseTableList(databaseId).then((tables) =>
        tables.map((table) => table.tableName),
      );
      tableCache.set(key, request);
    }
    const tables = await request;
    if (current === epoch && String(row.databaseId) === key)
      row.tables = tables;
  } catch {
    if (current === epoch && String(row.databaseId) === key) {
      tableCache.delete(key);
      row.error = '表清单加载失败，请重新选择连接重试';
    }
  } finally {
    if (current === epoch && String(row.databaseId) === key)
      row.loading = false;
  }
}
function createRow(reference?: DataforgeDevApi.TableReference): LineageRow {
  return {
    key: `table-${++rowSequence}`,
    databaseId: reference?.databaseId,
    schemaName: reference?.schemaName,
    tableName: reference?.tableName ?? '',
    tables: [],
    loading: false,
  };
}
function reset() {
  ++epoch;
  tableCache.clear();
  error.value = '';
  parseMessage.value = '';
  const lineage = props.task.executionConfig?.tableLineage;
  rows.value = {
    upstream: (lineage?.upstream ?? []).map((reference) =>
      createRow(reference),
    ),
    downstream: (lineage?.downstream ?? []).map((reference) =>
      createRow(reference),
    ),
  };
  dependencies.value = [...(props.task.dependencyTaskIds ?? [])];
  for (const row of [...rows.value.upstream, ...rows.value.downstream])
    void loadTables(row);
}
watch(() => [props.task.id, props.task.revision, props.task.name], reset, {
  immediate: true,
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => {
    tableCache.clear();
    reset();
  },
);
onBeforeUnmount(() => {
  ++epoch;
  tableCache.clear();
});
function addRow() {
  if (
    !locked.value &&
    rows.value.upstream.length + rows.value.downstream.length < 100
  )
    rows.value[direction.value].push(createRow());
}
function removeRow(index: number) {
  if (!locked.value) rows.value[direction.value].splice(index, 1);
}
function chooseDatabase(row: LineageRow, value: unknown) {
  if (locked.value) return;
  const selected = props.databases.find(
    (database) => String(database.value) === String(value),
  );
  if (!selected) return;
  row.databaseId = selected.value;
  row.schemaName = undefined;
  row.tableName = '';
  void loadTables(row);
}
function chooseTable(row: LineageRow, value: unknown) {
  if (!locked.value && typeof value === 'string' && row.tables.includes(value))
    row.tableName = value;
}
function chooseDependencies(value: unknown) {
  if (
    !locked.value &&
    Array.isArray(value) &&
    value.every((id) => typeof id === 'string' && /^[1-9]\d*$/.test(id))
  )
    dependencies.value = value;
}
function cancel() {
  reset();
  emit('cancel');
}
function save() {
  if (locked.value || props.saving || pending.value) return;
  error.value = '';
  const all = [...rows.value.upstream, ...rows.value.downstream];
  if (
    all.some(
      (row) =>
        row.databaseId === undefined ||
        !props.databases.some(
          (database) => String(database.value) === String(row.databaseId),
        ) ||
        !row.tableName ||
        !row.tables.includes(row.tableName),
    )
  ) {
    error.value = '请为每个节点选择可用数据连接及其真实数据表。';
    return;
  }
  for (const list of [rows.value.upstream, rows.value.downstream])
    if (
      new Set(
        list.map(
          (row) =>
            `${row.databaseId}\u0000${row.schemaName ?? ''}\u0000${row.tableName}`,
        ),
      ).size !== list.length
    ) {
      error.value = '同一方向不能重复配置相同数据表。';
      return;
    }
  const references = (list: LineageRow[]): DataforgeDevApi.TableReference[] =>
    list.map((row) => ({
      databaseId: row.databaseId!,
      tableName: row.tableName,
      ...(row.schemaName ? { schemaName: row.schemaName } : {}),
    }));
  emit(
    'save',
    clone({
      executionConfig: {
        ...defaultExecutionConfig(),
        ...props.task.executionConfig,
        tableLineage: {
          upstream: references(rows.value.upstream),
          downstream: references(rows.value.downstream),
        },
      },
      dependencyTaskIds: dependencies.value,
    }),
  );
}
</script>

<template>
  <section class="workbench-lineage-panel">
    <header class="panel-heading">
      <strong>血缘维护</strong
      ><button
        type="button"
        aria-label="收起血缘维护"
        @click="emit('collapse')"
      >
        −
      </button>
    </header>
    <div class="panel-body">
      <div class="section-heading">
        <h3>当前对象</h3>
        <Button
          size="small"
          @click="
            parseMessage =
              '当前未配置自动血缘解析，请手动维护真实上下游表关系。'
          "
        >
          <img class="button-icon" :src="panelAssets.reparse" alt="" />重新解析
        </Button>
      </div>
      <p v-if="parseMessage" role="status" class="field-note">
        {{ parseMessage }}
      </p>
      <div class="identity-row">
        <label class="required">任务名称:</label
        ><Input :value="task.name || '-'" disabled aria-label="任务名称" />
      </div>
      <div class="identity-row">
        <label class="required">责任人:</label
        ><Input :value="task.ownerName || '-'" disabled aria-label="责任人" />
      </div>
      <div class="identity-row">
        <label class="required">联系电话:</label
        ><Input
          :value="task.contactNumber || '-'"
          disabled
          aria-label="联系电话"
        />
      </div>
      <h3>链路配置</h3>
      <div class="links-editor">
        <div class="lineage-tabs" role="tablist" aria-label="表血缘方向">
          <button
            type="button"
            role="tab"
            :aria-selected="direction === 'upstream'"
            @click="direction = 'upstream'"
          >
            上游节点（{{ rows.upstream.length }}）</button
          ><button
            type="button"
            role="tab"
            :aria-selected="direction === 'downstream'"
            @click="direction = 'downstream'"
          >
            下游节点（{{ rows.downstream.length }}）
          </button>
        </div>
        <div class="links-toolbar">
          <span>{{
            direction === 'upstream'
              ? '以下节点的数据将流入当前任务'
              : '当前任务的数据将流入以下节点'
          }}</span
          ><Button
            :disabled="
              locked || rows.upstream.length + rows.downstream.length >= 100
            "
            size="small"
            @click="addRow"
          >
            <img class="button-icon" :src="panelAssets.addNode" alt="" />添加{{
              direction === 'upstream' ? '上游' : '下游'
            }}节点
          </Button>
        </div>
        <table>
          <thead>
            <tr>
              <th>数据连接信息</th>
              <th>对象表名</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows[direction]" :key="row.key">
              <td>
                <Select
                  :value="
                    row.databaseId === undefined
                      ? undefined
                      : String(row.databaseId)
                  "
                  :options="databaseOptions(row)"
                  :disabled="locked"
                  aria-label="数据连接信息"
                  placeholder="选择数据连接"
                  show-search
                  option-filter-prop="label"
                  @update:value="(value) => chooseDatabase(row, value)"
                />
              </td>
              <td>
                <Select
                  :value="row.tableName || undefined"
                  :options="tableOptions(row)"
                  :loading="row.loading"
                  :disabled="locked || row.databaseId === undefined"
                  aria-label="对象表名"
                  placeholder="选择对象表"
                  show-search
                  option-filter-prop="label"
                  @update:value="(value) => chooseTable(row, value)"
                /><span v-if="row.error" class="row-error" role="alert">{{
                  row.error
                }}</span>
              </td>
              <td>
                <button
                  type="button"
                  class="delete-link"
                  :disabled="locked"
                  :aria-label="`删除${direction === 'upstream' ? '上游' : '下游'}节点 ${index + 1}`"
                  @click="removeRow(index)"
                >
                  删除
                </button>
              </td>
            </tr>
            <tr v-if="!rows[direction].length">
              <td colspan="3" class="empty-links">
                暂无{{ direction === 'upstream' ? '上游' : '下游' }}节点
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="section-heading">
        <h3>血缘预览</h3>
        <Button size="small" @click="detailsOpen = true">
          <img class="button-icon" :src="panelAssets.details" alt="" />血缘详情
        </Button>
      </div>
      <div
        class="lineage-preview"
        :class="{ 'has-downstream': complete.downstream.length > 0 }"
      >
        <div v-if="complete.upstream.length" class="preview-column">
          <div
            v-for="row in complete.upstream"
            :key="row.key"
            class="table-node"
          >
            <b :title="row.tableName">{{ row.tableName }}</b
            ><span>{{ databaseName(row.databaseId) }}</span>
          </div>
        </div>
        <span
          v-if="complete.upstream.length"
          class="flow-arrow"
          aria-hidden="true"
        ></span>
        <div class="task-node">
          <span class="node-type">数据开发</span>
          <div>
            <b>{{ task.name || '当前任务' }}</b
            ><span>数据连接类型：{{ task.dbType || task.taskType || '-' }}</span
            ><span
              >最近执行状态：{{ task.latestRecord?.status || '未执行' }}</span
            >
          </div>
        </div>
        <span
          v-if="complete.downstream.length"
          class="flow-arrow"
          aria-hidden="true"
        ></span>
        <div v-if="complete.downstream.length" class="preview-column">
          <div
            v-for="row in complete.downstream"
            :key="row.key"
            class="table-node"
          >
            <b :title="row.tableName">{{ row.tableName }}</b
            ><span>{{ databaseName(row.databaseId) }}</span>
          </div>
        </div>
      </div>
      <p v-if="task.status === 1" class="field-note">
        任务已上线，请先停用任务后维护表血缘。
      </p>
      <details class="advanced-dependencies">
        <summary>高级：任务执行依赖</summary>
        <Select
          :value="dependencies.map(String)"
          :options="dependencyOptions"
          :disabled="locked"
          mode="multiple"
          aria-label="上游任务依赖"
          placeholder="选择必须先成功的任务"
          @update:value="chooseDependencies"
        />
        <p class="field-note">
          任务依赖控制执行顺序，与上方的数据表血缘分别保存。
        </p>
      </details>
      <p v-if="error" class="panel-error" role="alert">{{ error }}</p>
    </div>
    <footer class="panel-footer">
      <Button :disabled="saving" @click="cancel">取消</Button
      ><Button
        :disabled="locked || pending"
        :loading="saving"
        type="primary"
        ghost
        @click="save"
      >
        保存
      </Button>
    </footer>
    <Modal
      v-model:open="detailsOpen"
      title="血缘详情"
      :footer="null"
      :width="880"
    >
      <div class="lineage-details">
        <h4>{{ task.name || '当前任务' }}</h4>
        <div v-for="side in ['upstream', 'downstream'] as const" :key="side">
          <h4>{{ side === 'upstream' ? '上游数据表' : '下游数据表' }}</h4>
          <ul v-if="complete[side].length">
            <li v-for="row in complete[side]" :key="row.key">
              {{ databaseName(row.databaseId) }} /
              {{ row.schemaName ? `${row.schemaName}.` : ''
              }}{{ row.tableName }}
            </li>
          </ul>
          <p v-else>暂无节点</p>
        </div>
      </div>
    </Modal>
  </section>
</template>

<style scoped>
.button-icon {
  width: 14px;
  height: 14px;
  margin-right: 5px;
  vertical-align: -2px;
  object-fit: contain;
}

.workbench-lineage-panel {
  --panel-primary: #2865ff;

  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  color: #606266;
  background: #f7f7f7;
  border-left: 1px solid #e0e5ee;
}

.panel-heading {
  display: flex;
  flex: 0 0 42px;
  align-items: center;
  justify-content: space-between;
  padding: 0 11px;
  border-bottom: 1px solid #e0e5ee;
}

.panel-heading strong {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--panel-primary);
}

.panel-heading strong::before {
  width: 6px;
  height: 16px;
  content: '';
  background: var(--panel-primary);
  border-radius: 4px;
}

.panel-heading button {
  padding: 2px 7px;
  color: #8c9aad;
  background: transparent;
  border: 0;
}

.panel-body {
  flex: 1;
  min-height: 0;
  padding: 0 10px 12px;
  overflow: auto;
}

h3 {
  margin: 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--panel-primary);
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-heading :deep(.ant-btn),
.links-toolbar :deep(.ant-btn) {
  height: 25px;
  padding: 0 8px;
  color: var(--panel-primary);
  background: #edf3ff;
  border-color: var(--panel-primary);
  border-radius: 2px;
}

.identity-row {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  margin: 8px 0;
  font-size: 13px;
}

.identity-row label {
  white-space: nowrap;
}

.identity-row .required::before {
  margin-right: 4px;
  color: #ff4d4f;
  content: '*';
}

.identity-row :deep(.ant-input) {
  height: 26px;
  padding: 2px 10px;
  border-radius: 2px;
}

.links-editor {
  background: white;
  border: 1px solid #dfe6f2;
}

.lineage-tabs {
  display: flex;
  gap: 20px;
  padding: 0 10px;
  border-bottom: 1px solid #e3e7ee;
}

.lineage-tabs button {
  padding: 7px 0 5px;
  font-size: 13px;
  color: #606266;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
}

.lineage-tabs button[aria-selected='true'] {
  color: var(--panel-primary);
  border-bottom-color: var(--panel-primary);
}

.links-toolbar {
  display: flex;
  gap: 4px;
  align-items: center;
  justify-content: space-between;
  padding: 4px 7px;
  font-size: 11px;
  color: #909399;
}

table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
}

th {
  height: 32px;
  font-size: 12px;
  background: #f4f6fb;
}

th,
td {
  padding: 3px;
  text-align: center;
  border: 1px solid #e4e8ef;
}

th:last-child {
  width: 52px;
}

td :deep(.ant-select) {
  width: 100%;
  text-align: left;
}

td :deep(.ant-select-selector) {
  min-height: 27px;
  padding: 0 5px !important;
  border: none !important;
  box-shadow: none !important;
}

.delete-link {
  padding: 4px;
  color: #ff4d4f;
  background: transparent;
  border: 0;
}

.delete-link:disabled {
  color: #b8bec8;
}

.empty-links {
  height: 40px;
  font-size: 12px;
  color: #a0a6b3;
}

.row-error,
.panel-error {
  font-size: 11px;
  color: #ff4d4f;
}

.lineage-preview {
  display: flex;
  gap: 5px;
  align-items: center;
  justify-content: center;
  min-height: 249px;
  padding: 10px 8px;
  background: #e7edf6;
  border: 1px solid #dfe6f2;
}

.preview-column {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.table-node {
  padding: 5px 4px;
  font-size: 8px;
  background: #edf6ff;
  border-radius: 2px;
}

.table-node b,
.task-node b {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
  white-space: nowrap;
}

.table-node span {
  display: block;
  padding: 3px;
  margin-top: 3px;
  overflow-wrap: anywhere;
  background: #fff;
}

.task-node {
  display: flex;
  flex: 1.15;
  min-width: 0;
  max-width: 190px;
  padding: 0 4px 0 0;
  font-size: 8px;
  background: #effaf6;
  border-radius: 2px;
}

.task-node > div {
  min-width: 0;
  padding: 5px 4px;
}

.task-node span:not(.node-type) {
  display: block;
  margin-top: 5px;
  font-size: 7px;
  color: #9ba9a5;
}

.node-type {
  flex: 0 0 12px;
  padding: 9px 2px;
  color: white;
  text-align: center;
  background: #10b875;
  writing-mode: vertical-lr;
}

.flow-arrow {
  position: relative;
  flex: 0 0 25px;
  height: 1px;
  background: #c7d9ec;
}

.flow-arrow::after {
  position: absolute;
  top: -2px;
  right: 0;
  width: 4px;
  height: 4px;
  content: '';
  border-top: 1px solid #c7d9ec;
  border-right: 1px solid #c7d9ec;
  transform: rotate(45deg);
}

.has-downstream .flow-arrow {
  flex-basis: 10px;
}

.field-note {
  margin: 8px 0;
  font-size: 11px;
  line-height: 1.5;
  color: #909399;
}

.advanced-dependencies {
  padding-top: 10px;
  margin-top: 18px;
  border-top: 1px solid #e0e5ee;
}

.advanced-dependencies summary {
  margin-bottom: 10px;
  font-size: 12px;
  color: #7a879c;
  cursor: pointer;
}

.advanced-dependencies :deep(.ant-select) {
  width: 100%;
}

.panel-footer {
  display: flex;
  flex: 0 0 44px;
  gap: 20px;
  align-items: center;
  justify-content: flex-end;
  padding: 0 11px;
  border-top: 1px solid #e0e5ee;
}

.panel-footer :deep(.ant-btn) {
  min-width: 52px;
  height: 26px;
  border-radius: 2px;
}

.lineage-details {
  color: #606266;
}

.lineage-details h4 {
  margin: 15px 0;
  font-weight: 600;
}

.lineage-details li {
  margin: 7px 0;
  overflow-wrap: anywhere;
}

:global(.dark) .workbench-lineage-panel {
  color: hsl(var(--foreground));
  background: hsl(var(--background));
}

@media (max-width: 420px) {
  .links-toolbar {
    flex-wrap: wrap;
  }

  .lineage-tabs {
    gap: 12px;
  }
}
</style>

<style scoped>
table thead th,
table tbody td {
  font-size: 12px;
}
</style>
