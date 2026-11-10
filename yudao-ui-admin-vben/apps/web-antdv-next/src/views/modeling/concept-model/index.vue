<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ConceptModelApi } from '#/api/modeling/concept-model';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteConceptModel,
  getConceptModelPage,
  updateConceptModelStatus,
} from '#/api/modeling/concept-model';

import { useModelingRequest } from '../modelcommon/use-modeling-request';
import Form from './modules/form.vue';

defineOptions({ name: 'ModelingConceptModel' });
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const active = ref(true);
const busyIds = ref(new Set<string>());
let contextRevision = 0;
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const requests = useModelingRequest(() => `${scope.value}/${active.value}`);
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
function refresh() {
  if (active.value) gridApi.query();
}
function open(id?: ConceptModelApi.Id, readonly = false) {
  formModalApi.setData({ id, readonly }).open();
}

async function changeStatus(row: ConceptModelApi.Model) {
  const key = String(row.id);
  if (
    !active.value ||
    busyIds.value.has(key) ||
    !hasAccessByCodes(['modeling:concept-model:update'])
  )
    return;
  busyIds.value.add(key);
  const context = contextRevision;
  const next = row.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE';
  const action = next === 'ACTIVE' ? '启用' : '停用';
  try {
    try {
      await confirm(
        `确认${action}“${row.name}”？${next === 'ACTIVE' ? '启用后设计不可直接编辑。' : '停用后可继续编辑设计。'}`,
      );
    } catch {
      return;
    }
    if (!active.value || context !== contextRevision) return;
    await updateConceptModelStatus(row.id, row.revision, next);
    if (!active.value || context !== contextRevision) return;
    message.success(`已${action}`);
    refresh();
  } finally {
    if (context === contextRevision) busyIds.value.delete(key);
  }
}

async function remove(row: ConceptModelApi.Model) {
  const key = String(row.id);
  if (
    !active.value ||
    busyIds.value.has(key) ||
    !hasAccessByCodes(['modeling:concept-model:delete'])
  )
    return;
  busyIds.value.add(key);
  const context = contextRevision;
  try {
    await deleteConceptModel(row.id, row.revision);
    if (!active.value || context !== contextRevision) return;
    message.success('概念模型已删除');
    refresh();
  } finally {
    if (context === contextRevision) busyIds.value.delete(key);
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'keyword',
        label: '名称或编码',
        component: 'Input',
        componentProps: {
          allowClear: true,
          maxlength: 100,
          placeholder: '搜索概念模型',
        },
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '草稿', value: 'DRAFT' },
            { label: '已启用', value: 'ACTIVE' },
          ],
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
    columns: [
      { field: 'id', title: '编号', width: 105, showOverflow: 'tooltip' },
      {
        field: 'name',
        title: '概念模型',
        minWidth: 180,
        slots: { default: 'name' },
      },
      { field: 'code', title: '编码', minWidth: 170 },
      { field: 'dataDomainName', title: '数据板块', minWidth: 140 },
      { field: 'subjectDomainName', title: '主题域', minWidth: 140 },
      {
        field: 'description',
        title: '业务说明',
        minWidth: 200,
        showOverflow: false,
      },
      {
        field: 'status',
        title: '状态',
        width: 100,
        slots: { default: 'status' },
      },
      {
        field: 'updateTime',
        title: '更新时间',
        width: 175,
        formatter: ({ cellValue }) => formatDateTime(cellValue),
      },
      {
        title: '操作',
        field: 'actions',
        width: 260,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    proxyConfig: {
      ajax: {
        query: async ({ page }, values) => {
          const current = requests.begin();
          if (
            !active.value ||
            !hasAccessByCodes(['modeling:concept-model:query'])
          )
            return { list: [], total: 0 };
          const result = await getConceptModelPage({
            ...values,
            pageNo: page.currentPage,
            pageSize: page.pageSize,
          });
          // Vxe 的 query catch 不加载数据；过期返回不能覆盖新用户或新筛选的列表。
          if (!current()) throw new Error('概念模型查询已失效');
          return result;
        },
      },
    },
  } as VxeTableGridOptions<ConceptModelApi.Model>,
});

function resetContext() {
  contextRevision += 1;
  requests.begin();
  busyIds.value = new Set();
  formModalApi.close?.();
  gridApi.grid.loadData?.([]);
}
watch(
  scope,
  () => {
    resetContext();
    if (active.value) gridApi.reload();
  },
  { flush: 'sync' },
);
onDeactivated(() => {
  active.value = false;
  resetContext();
});
onBeforeUnmount(() => {
  active.value = false;
  contextRevision += 1;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    gridApi.reload();
  }
});
</script>

<template>
  <Page auto-content-height class="concept-model-page">
    <FormModal @success="refresh" />
    <Grid table-title="概念模型">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['modeling:concept-model:create'],
              onClick: () => open(),
            },
          ]"
        />
      </template>
      <template #name="{ row }">
        <div class="concept-model-name">
          <span class="concept-model-icon" aria-hidden="true">
            <IconifyIcon icon="lucide:waypoints" />
          </span>
          <span class="concept-model-label" :title="row.name">{{
            row.name
          }}</span>
        </div>
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 'ACTIVE' ? 'green' : 'default'">
          {{ row.status === 'ACTIVE' ? '已启用' : '草稿' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['modeling:concept-model:query'],
              onClick: () => open(row.id, true),
            },
            {
              label: '编辑',
              disabled: busyIds.has(String(row.id)),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              ifShow: row.status === 'DRAFT',
              auth: ['modeling:concept-model:update'],
              onClick: () => open(row.id),
            },
            {
              label: row.status === 'ACTIVE' ? '停用' : '启用',
              disabled: busyIds.has(String(row.id)),
              type: 'link',
              icon:
                row.status === 'ACTIVE'
                  ? 'ant-design:stop-outlined'
                  : 'ant-design:check-circle-outlined',
              auth: ['modeling:concept-model:update'],
              onClick: () => changeStatus(row),
            },
            {
              label: '删除',
              disabled: busyIds.has(String(row.id)),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              ifShow: row.status === 'DRAFT',
              auth: ['modeling:concept-model:delete'],
              popConfirm: {
                title: `确认删除“${row.name}”？`,
                confirm: () => remove(row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
.concept-model-page :deep(.vxe-table--scroll-x-handle),
.concept-model-page :deep(.vxe-table--scroll-y-handle) {
  scrollbar-color: #d4d7dc transparent;
  scrollbar-width: thin;
}

.concept-model-name {
  display: flex;
  gap: 5px;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding-inline-end: 20px;
  line-height: 20px;
}

.concept-model-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  font-size: 11px;
  color: white;
  background: hsl(var(--primary));
  border-radius: 2px;
}

.concept-model-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
