<script setup lang="ts">
import type { TableEmits } from 'antdv-next';

import type { GovernanceStandardCodeItemApi as ItemApi } from '#/api/governance/standardcodeitem';
import type { StandardCodeMapping } from '#/api/governance/standardcodeset/mapping';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon, Plus } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  message,
  Modal,
  Table,
} from 'antdv-next';

import {
  deleteStandardCodeItem,
  getStandardCodeItemPage,
} from '#/api/governance/standardcodeitem';
import {
  deleteCodeMapping,
  getCodeMappingPage,
} from '#/api/governance/standardcodeset/mapping';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardPagination from '../../../standardcommon/standard-pagination.vue';
import StandardToolbar from '../../../standardcommon/standard-toolbar.vue';
import ItemForm from './item-form.vue';
import MappingForm from './mapping-form.vue';
type Id = ItemApi.Id;
type Row = ItemApi.StandardCodeItem | StandardCodeMapping;
const props = defineProps<{
  codeSetId?: Id;
  dictType?: string;
  kind: 'items' | 'mapping';
}>();
const emit = defineEmits<{ change: [] }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const can = (action: string) =>
  hasAccessByCodes([
    `governance:standard-code-set:${props.kind === 'mapping' ? 'update' : action}`,
  ]);
const scope = computed(
  () =>
    `${props.codeSetId}/${props.kind}/${access.accessToken}/${access.refreshToken}/${user.userInfo?.userId}/${access.accessCodes.join(',')}`,
);
const rows = ref<Row[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const sortField = ref<'createTime' | 'id'>('createTime');
const sortOrder = ref<'asc' | 'desc'>('desc');
const definitions = computed(() => [
  { key: 'id', title: '编号', width: 90, sorter: true },
  ...(props.kind === 'items'
    ? [
        { key: 'value', title: '代码值', width: 240 },
        { key: 'label', title: '代码名称', width: 240 },
      ]
    : [
        { key: 'originalValue', title: '原始值', width: 210 },
        { key: 'codeName', title: '代码名', width: 220 },
        { key: 'codeValue', title: '代码值', width: 180 },
      ]),
  { key: 'creatorName', title: '创建人', width: 180 },
  { key: 'createTime', title: '创建时间', width: 180, sorter: true },
  { key: 'actions', title: '操作', width: 300, fixed: 'right' as const },
]);
const visibleColumns = ref(definitions.value.map((c) => c.key));
const tableSortOrder = computed(() =>
  sortOrder.value === 'asc' ? ('ascend' as const) : ('descend' as const),
);
const columns = computed(() =>
  definitions.value
    .filter((c) => visibleColumns.value.includes(c.key))
    .map((c) => ({
      ...c,
      dataIndex: c.key,
      ellipsis: c.key !== 'actions',
      sortOrder: c.key === sortField.value ? tableSortOrder.value : undefined,
      sorter: c.sorter,
    })),
);
const request = useObjectRequest(
  () => scope.value,
  () => {
    rows.value = [];
    total.value = 0;
  },
);
const actionRequest = useObjectRequest(() => scope.value);
const [FormModal, modalApi] = useVbenModal({
  connectedComponent: props.kind === 'items' ? ItemForm : MappingForm,
  destroyOnClose: true,
});
async function load() {
  const current = request.begin();
  rows.value = [];
  error.value = '';
  if (!props.codeSetId || !can('query')) {
    total.value = 0;
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const params = {
      codeSetId: props.codeSetId,
      pageNo: page.value,
      pageSize: pageSize.value,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    };
    const result = await (props.kind === 'items'
      ? getStandardCodeItemPage(params)
      : getCodeMappingPage(params));
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
    if (page.value > 1 && result.list.length === 0 && result.total) {
      page.value = Math.ceil(result.total / pageSize.value);
      await load();
    }
  } catch {
    if (current()) error.value = '数据加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
function open(row?: Row, readonly = false) {
  modalApi
    .setData({
      id: row?.id,
      codeSetId: props.codeSetId,
      dictType: props.dictType,
      readonly,
    })
    .open();
}
async function saved() {
  emit('change');
  await load();
}
function remove(row: Row) {
  const current = actionRequest.begin();
  const codeSetId = props.codeSetId;
  if (!codeSetId || row.id === undefined) return;
  const id = row.id;
  Modal.confirm({
    title: `确定删除此${props.kind === 'items' ? '代码' : '代码映射'}吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (!current() || !can('delete')) return;
      if (props.kind === 'mapping') await deleteCodeMapping(codeSetId, id);
      else await deleteStandardCodeItem(id);
      if (current()) {
        message.success('删除成功');
        await saved();
      }
    },
  });
}
function cellText(record: object, key: unknown) {
  return (record as Record<string, unknown>)[String(key)] ?? '-';
}
const changeTable: TableEmits<Row>['change'] = (_p, _f, sorter) => {
  const value = Array.isArray(sorter) ? sorter[0] : sorter;
  sortField.value = value?.field === 'id' ? 'id' : 'createTime';
  sortOrder.value = value?.order === 'ascend' ? 'asc' : 'desc';
  page.value = 1;
  void load();
};
watch(
  scope,
  () => {
    page.value = 1;
    void modalApi.close?.();
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <FormModal @success="saved" />
  <StandardToolbar
    class="standard-code-records-toolbar"
    v-model:visible-columns="visibleColumns"
    :columns="definitions"
    hide-search
    settings-icon="lucide:settings"
    @refresh="load"
  >
    <template #title>
      <Button
        v-if="can('create')"
        type="primary"
        size="small"
        :disabled="!codeSetId"
        @click="open()"
      >
        <template #icon><Plus :size="12" /></template>
        新增
      </Button>
      <span v-else aria-hidden="true"></span>
    </template>
  </StandardToolbar>
  <Alert v-if="error" :message="error" type="error" class="mb-3" />
  <Table
    class="standard-table standard-code-records"
    :columns="columns"
    :data-source="rows"
    :pagination="false"
    :loading="loading"
    :row-key="(row) => String(row.id)"
    :scroll="{ x: kind === 'items' ? 1120 : 1350 }"
    @change="changeTable"
  >
    <template #emptyText>
      <div class="standard-empty">
        <img src="/images/quality-reference/empty.png" alt="" />
        <span>暂无记录</span>
      </div>
    </template>
    <template #bodyCell="{ column, record }">
      <span v-if="column.key === 'createTime'">{{
        record.createTime
          ? formatDate(record.createTime, 'YYYY-MM-DD HH:mm')
          : '-'
      }}</span>
      <div v-else-if="column.key === 'actions'" class="standard-row-actions">
        <Button type="link" v-if="can('update')" @click="open(record)">
          <IconifyIcon icon="lucide:square-pen" />修改
        </Button>
        <Button type="link" danger v-if="can('delete')" @click="remove(record)">
          <IconifyIcon icon="lucide:trash-2" />删除
        </Button>
        <Button type="link" @click="open(record, true)">
          <IconifyIcon icon="lucide:eye" />详情
        </Button>
      </div>
      <span v-else>{{ cellText(record, column.key) }}</span>
    </template>
  </Table>
  <StandardPagination
    v-model:page="page"
    v-model:page-size="pageSize"
    :total="total"
    @change="load"
  />
</template>
<style scoped>
.standard-code-records {
  min-height: 360px;
}

.standard-code-records :deep(.ant-table-container) {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.standard-code-records :deep(.ant-table-thead > tr > th) {
  padding-block: 7px;
  line-height: 20px;
  color: hsl(var(--foreground));
  background: hsl(var(--accent));
  border-bottom: 1px solid hsl(var(--border));
}

.standard-code-records :deep(.ant-table-thead > tr > th:not(:last-child)::before) {
  background-color: hsl(var(--border));
}

.standard-code-records :deep(.ant-table-thead > tr > th),
.standard-code-records :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.standard-code-records-toolbar {
  min-height: 30px;
  margin-bottom: 8px;
}

.standard-code-records-toolbar :deep(.standard-toolbar-tools) {
  gap: 8px;
}

.standard-code-records-toolbar :deep(.standard-toolbar-tools .ant-btn) {
  width: 26px;
  min-width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 50%;
}

.standard-code-records-toolbar :deep(.standard-toolbar-tools svg) {
  width: 12px;
  height: 12px;
}

.standard-code-records :deep(.ant-table-tbody > tr > td) {
  padding-block: 8px;
  line-height: 24px;
}

.standard-code-records :deep(.standard-row-actions .ant-btn) {
  height: 24px;
}

.standard-row-actions svg {
  width: 14px;
  height: 14px;
}
</style>
