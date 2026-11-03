<script setup lang="ts">
import type { TableEmits } from 'antdv-next';

import type { GovernanceStandardCodeItemApi as ItemApi } from '#/api/governance/standardcodeitem';
import type { StandardCodeMapping } from '#/api/governance/standardcodeset/mapping';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Dropdown,
  Menu,
  MenuItem,
  message,
  Modal,
  Table,
} from 'antdv-next';

import {
  deleteStandardCodeItem,
  deleteStandardCodeItemList,
  getStandardCodeItemPage,
  getStandardCodeItemTree,
} from '#/api/governance/standardcodeitem';
import {
  deleteCodeMapping,
  getCodeMappingPage,
} from '#/api/governance/standardcodeset/mapping';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardPagination from '../../../standardcommon/standard-pagination.vue';
import StandardToolbar from '../../../standardcommon/standard-toolbar.vue';
import { buildCodeItemTree } from '../model';
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
const treeMode = ref(false);
const includeHistory = ref(false);
const selectedIds = ref<Id[]>([]);
const expandedKeys = ref<Id[]>([]);
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
      sortOrder:
        !treeMode.value && c.key === sortField.value
          ? tableSortOrder.value
          : undefined,
      sorter: !treeMode.value && c.sorter,
    })),
);
const request = useObjectRequest(
  () => scope.value,
  () => {
    rows.value = [];
    total.value = 0;
    selectedIds.value = [];
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
  selectedIds.value = [];
  error.value = '';
  if (!props.codeSetId || !can('query')) {
    total.value = 0;
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    if (treeMode.value && props.kind === 'items') {
      const result = buildCodeItemTree(
        await getStandardCodeItemTree({
          codeSetId: props.codeSetId,
          includeHistory: includeHistory.value,
        }),
      );
      if (current()) {
        rows.value = result;
        total.value = result.length;
        expandedKeys.value = result.map((row) => String(row.id));
      }
    } else {
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
    }
  } catch {
    if (current()) error.value = '数据加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
function open(row?: Row, readonly = false, parentId?: Id) {
  modalApi
    .setData({
      id: row?.id,
      codeSetId: props.codeSetId,
      dictType: props.dictType,
      readonly,
      parentId,
    })
    .open();
}
async function saved() {
  emit('change');
  await load();
}
function remove(row?: Row) {
  const current = actionRequest.begin();
  const codeSetId = props.codeSetId;
  const ids = row?.id === undefined ? [...selectedIds.value] : [row.id];
  if (!codeSetId || ids.length === 0) return;
  Modal.confirm({
    title: row
      ? `确定删除此${props.kind === 'items' ? '代码' : '代码映射'}吗？`
      : `确定删除选中的 ${ids.length} 个码值吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (!current() || !can('delete')) return;
      if (props.kind === 'mapping') await deleteCodeMapping(codeSetId, ids[0]!);
      else if (row) await deleteStandardCodeItem(ids[0]!);
      else await deleteStandardCodeItemList(ids);
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
watch(treeMode, () => {
  page.value = 1;
  void load();
});
</script>
<template>
  <FormModal @success="saved" />
  <StandardToolbar
    :title="kind === 'items' ? '代码字典' : '代码映射'"
    v-model:visible-columns="visibleColumns"
    :columns="definitions"
    hide-search
    @refresh="load"
  >
    <Button
      v-if="can('create')"
      type="primary"
      :disabled="!codeSetId"
      @click="open()"
    >
      <IconifyIcon icon="lucide:plus" />新增
    </Button>
    <Dropdown v-if="kind === 'items'">
      <Button
        ><template #icon
          ><IconifyIcon icon="lucide:ellipsis-vertical" /></template
        >更多</Button
      ><template #popupRender>
        <Menu @click="treeMode = !treeMode">
          <MenuItem key="tree">
            <template #icon>
              <IconifyIcon
                :icon="treeMode ? 'lucide:list' : 'lucide:git-branch'"
              />
            </template>
            {{ treeMode ? '返回代码字典' : '层级与有效期' }}
          </MenuItem>
        </Menu>
      </template>
    </Dropdown>
    <template v-if="treeMode">
      <Checkbox v-model:checked="includeHistory" @change="load">
        显示历史码值 </Checkbox
      ><Button
        v-if="can('delete')"
        danger
        :disabled="!selectedIds.length"
        @click="remove()"
      >
        <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
        批量删除
      </Button>
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
    :row-selection="
      treeMode
        ? {
            selectedRowKeys: selectedIds,
            onChange: (keys) => (selectedIds = keys as Id[]),
            checkStrictly: true,
          }
        : undefined
    "
    :expandable="
      treeMode
        ? {
            expandedRowKeys: expandedKeys,
            onExpandedRowsChange: (keys) => (expandedKeys = [...keys] as Id[]),
          }
        : undefined
    "
    @change="changeTable"
  >
    <template #emptyText>
      <div class="standard-empty">
        <img src="/images/quality-reference/empty.png" alt="" /><span
          >暂无记录</span
        >
      </div>
    </template>
    <template #bodyCell="{ column, record }">
      <span v-if="column.key === 'createTime'">{{
        record.createTime
          ? formatDate(record.createTime, 'YYYY-MM-DD HH:mm')
          : '-'
      }}</span>
      <div v-else-if="column.key === 'actions'" class="standard-row-actions">
        <Button
          type="link"
          v-if="treeMode && can('create')"
          @click="open(undefined, false, record.id)"
        >
          <IconifyIcon
            icon="lucide:plus"
            class="mr-1 inline-block size-4 align-middle"
          />
          新增下级
        </Button>
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
    v-if="!treeMode"
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

:deep(.standard-code-records .ant-table-container) {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

:deep(.standard-code-records .ant-table-thead > tr > th) {
  color: hsl(var(--foreground));
  background: hsl(var(--accent));
  border-bottom: 1px solid hsl(var(--border));
}

:deep(.standard-code-records .ant-table-thead > tr > th:not(:last-child)::before) {
  background-color: hsl(var(--border));
}

:deep(.standard-code-records .ant-table-thead > tr > th),
:deep(.standard-code-records .ant-table-tbody > tr > td) {
  font-size: 12px;
}

.standard-row-actions svg {
  width: 14px;
  height: 14px;
}
</style>
