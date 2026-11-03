<script lang="ts" setup>
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Button, DatePicker, Input, message, Table } from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import {
  addMasterData,
  deleteMasterData,
  getMasterDataPage,
  updateMasterData,
} from '#/api/governance/masterdata';

import { fieldTypePlaceholder } from '../data';

defineOptions({ name: 'GovernanceMasterDataMaintain' });

const model = ref<GovernanceMasterDataApi.Model>();
const columns = computed(() => model.value?.columns ?? []);

/** 查询条件值（按字段名） */
const queryValues = reactive<Record<string, string>>({});

const tableColumns = computed(() => [
  ...columns.value.map((column) => ({
    title: column.commentStr || column.name,
    dataIndex: column.name,
    key: column.name,
    ellipsis: true,
    minWidth: column.fieldType === 9 ? 180 : 120,
  })),
  { title: '操作', key: 'actions', width: 180, fixed: 'right' as const },
]);

/** 数据分页 */
const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });

async function loadData() {
  if (!model.value?.id) {
    return;
  }
  loading.value = true;
  try {
    const result = await getMasterDataPage({
      modelId: model.value.id,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      conditions: Object.entries(queryValues)
        .filter(
          ([, value]) => value !== '' && value !== undefined && value !== null,
        )
        .map(([name, value]) => ({ name, value })),
    });
    rows.value = result.list ?? [];
    pagination.total = Number(result.total ?? 0);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.pageNo = 1;
  loadData();
}

function handleReset() {
  Object.assign(
    queryValues,
    Object.fromEntries(Object.keys(queryValues).map((key) => [key, ''])),
  );
  handleSearch();
}

function handlePageChange(page: number, pageSize: number) {
  pagination.pageNo = page;
  pagination.pageSize = pageSize;
  loadData();
}

/** ==================== 行编辑 ==================== */
const editIsCreate = ref(true);
const editModel = reactive<Record<string, any>>({});

function resetEditModel(next?: Record<string, any>) {
  Object.assign(
    editModel,
    Object.fromEntries(Object.keys(editModel).map((key) => [key, undefined])),
  );
  if (next) {
    Object.assign(editModel, next);
  }
}

function isDateTimeColumn(column?: GovernanceMasterDataApi.Column) {
  return column?.fieldType === 9;
}

function isDateTimeField(key: unknown) {
  return (
    typeof key === 'string' &&
    isDateTimeColumn(columns.value.find((item) => item.name === key))
  );
}

function formatDataCell(columnName: string, row: Record<string, any>) {
  const value = row[columnName];
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  return isDateTimeField(columnName)
    ? formatDateTime(value) || '-'
    : String(value);
}

function isColumnReadonly(column: GovernanceMasterDataApi.Column) {
  // 编辑时主键作为定位条件不可修改
  return !editIsCreate.value && column.pk === 1;
}

function handleAddRow() {
  editIsCreate.value = true;
  resetEditModel();
  rowEditModalApi.open();
}

function handleEditRow(row: Record<string, any>) {
  editIsCreate.value = false;
  const next: Record<string, any> = { ...row };
  for (const column of columns.value) {
    const name = column.name;
    if (
      !name ||
      !isDateTimeColumn(column) ||
      next[name] === undefined ||
      next[name] === null ||
      next[name] === ''
    ) {
      continue;
    }
    next[name] = formatDateTime(next[name]) || next[name];
  }
  resetEditModel(next);
  rowEditModalApi.open();
}

function buildEditPayload() {
  const payload: Record<string, any> = {};
  for (const column of columns.value) {
    if (column.name) {
      payload[column.name] = editModel[column.name];
    }
  }
  return payload;
}

async function handleSaveRow() {
  for (const column of columns.value) {
    const value = editModel[column.name!];
    if (
      column.nullable === 0 &&
      (value === undefined || value === null || value === '')
    ) {
      message.warning(`【${column.commentStr || column.name}】不能为空`);
      return false;
    }
  }
  const modelId = model.value!.id!;
  const payload = buildEditPayload();
  if (editIsCreate.value) {
    await addMasterData(modelId, [payload]);
    message.success('数据已新增');
  } else {
    await updateMasterData(modelId, [payload]);
    message.success('数据已更新');
  }
  loadData();
  return true;
}

async function handleDeleteRow(row: Record<string, any>) {
  await deleteMasterData(model.value!.id!, [row]);
  message.success('数据已删除');
  loadData();
}

const getTitle = computed(() =>
  model.value
    ? `主数据维护 - ${model.value.tableCn ?? model.value.tableName}`
    : '主数据维护',
);

const [ModalComponent, modalApi] = useVbenModal({
  showCancelButton: false,
  showConfirmButton: false,
  class: 'w-[1180px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      rows.value = [];
      Object.assign(
        queryValues,
        Object.fromEntries(Object.keys(queryValues).map((key) => [key, ''])),
      );
      return;
    }
    model.value = modalApi.getData() as GovernanceMasterDataApi.Model;
    pagination.pageNo = 1;
    modalApi.lock();
    try {
      await loadData();
    } finally {
      modalApi.unlock();
    }
  },
});

const getRowEditTitle = computed(() =>
  editIsCreate.value ? '新增数据' : '编辑数据',
);

const [RowEditModal, rowEditModalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    rowEditModalApi.lock();
    try {
      const saved = await handleSaveRow();
      if (saved) {
        await rowEditModalApi.close();
      }
    } finally {
      rowEditModalApi.unlock();
    }
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div class="px-2 pb-2">
      <!-- 查询条件：全部字段可查（字符串模糊，其他精确） -->
      <div v-if="columns.length > 0" class="query-bar">
        <div
          v-for="column in columns"
          :key="column.name"
          class="query-bar__item"
        >
          <span class="query-bar__label">
            {{ column.commentStr || column.name }}
          </span>
          <DatePicker
            v-if="isDateTimeColumn(column)"
            v-model:value="queryValues[column.name!]"
            allow-clear
            class="w-52"
            format="YYYY-MM-DD HH:mm:ss"
            :placeholder="fieldTypePlaceholder(column.fieldType)"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
          />
          <Input
            v-else
            v-model:value="queryValues[column.name!]"
            allow-clear
            class="w-40"
            :placeholder="fieldTypePlaceholder(column.fieldType)"
            @press-enter="handleSearch"
          />
        </div>
        <Button size="small" type="primary" @click="handleSearch">查询</Button>
        <Button size="small" @click="handleReset">重置</Button>
      </div>

      <!-- 工具栏 -->
      <div class="mb-3 flex items-center justify-between">
        <Button size="small" type="primary" @click="handleAddRow">
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          新增
        </Button>
        <Button size="small" @click="loadData">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          刷新
        </Button>
      </div>

      <!-- 动态数据表格 -->
      <Table
        :columns="tableColumns"
        :data-source="rows"
        :loading="loading"
        :pagination="{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          onChange: handlePageChange,
        }"
        :scroll="{ x: 900, y: 420 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="isDateTimeField(column.key)">
            {{ formatDataCell(String(column.key), record as any) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <TableAction
              :actions="[
                {
                  label: '编辑',
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  onClick: handleEditRow.bind(null, record as any),
                },
                {
                  label: '删除',
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  popConfirm: {
                    title: '确认删除该行数据吗？',
                    confirm: handleDeleteRow.bind(null, record as any),
                  },
                },
              ]"
            />
          </template>
        </template>
      </Table>
    </div>

    <!-- 行编辑弹窗：按字段配置动态渲染 -->
    <RowEditModal :title="getRowEditTitle">
      <div
        class="row-edit-form governance-modal-form governance-form-item-horizontal"
      >
        <div class="form-item__row">
          <div v-for="column in columns" :key="column.name" class="form-item">
            <label
              class="form-item__label"
              :class="{ 'form-item__label--required': column.nullable === 0 }"
            >
              {{ column.commentStr || column.name }}
              <span v-if="column.pk === 1" class="text-amber-500">(主键)</span>
            </label>
            <DatePicker
              v-if="isDateTimeColumn(column)"
              v-model:value="editModel[column.name!]"
              :disabled="isColumnReadonly(column)"
              allow-clear
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              :placeholder="fieldTypePlaceholder(column.fieldType)"
              show-time
              value-format="YYYY-MM-DD HH:mm:ss"
            />
            <Input
              v-else
              v-model:value="editModel[column.name!]"
              :disabled="isColumnReadonly(column)"
              allow-clear
              :placeholder="fieldTypePlaceholder(column.fieldType)"
            />
          </div>
        </div>
      </div>
    </RowEditModal>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';

.query-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  align-items: center;
  padding: 12px 14px;
  margin-bottom: 12px;
  background: hsl(var(--muted) / 40%);
  border-radius: 8px;
}

.query-bar__item {
  display: flex;
  gap: 6px;
  align-items: center;
}

.query-bar__label {
  font-size: 14px;
  color: hsl(var(--foreground));
}

.row-edit-form {
  max-height: 460px;
  overflow-y: auto;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
