<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { onUnmounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { useAccessStore, useUserStore } from '@vben/stores';
import { isEmpty } from '@vben/utils';

import { message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDatabaseType,
  deleteDatabaseTypeList,
  getDatabaseTypePage,
  updateDatabaseTypeStatus,
} from '#/api/dataforge/databasetype';
import { ConnectionIcon } from '#/components/connection-icon';
import { $t } from '#/locales';

import { useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const updatingStatusIds = ref(new Set<string>());
let appliedStatus: number | undefined;
let queryGeneration = 0;
let dataRevision = 0;
let latestQuery: ReturnType<typeof getDatabaseTypePage> | undefined;
let scopeRevision = 0;

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

/** 查看连接器详情 */
function handleDetail(row: FoundationDatabaseTypeApi.DatabaseType) {
  detailModalApi.setData({ id: row.id }).open();
}

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 创建连接器定义 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 编辑连接器定义 */
function handleEdit(row: FoundationDatabaseTypeApi.DatabaseType) {
  formModalApi.setData(row).open();
}

/** 更新连接器状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationDatabaseTypeApi.DatabaseType,
) {
  const id = `${scopeRevision}:${String(row.id)}`;
  if (
    updatingStatusIds.value.has(id) ||
    !hasAccessByCodes(['dataforge:database-type:update'])
  ) {
    return;
  }
  updatingStatusIds.value.add(id);
  const scope = scopeRevision;
  try {
    const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
    let update: Promise<void> | undefined;
    try {
      await confirm(`确认要${text}"${row.typeName}"吗?`, {
        beforeClose: ({ isConfirm }) => {
          if (isConfirm && !update && scope === scopeRevision) {
            update = submitStatusChange(row, newStatus, text, scope);
          }
        },
      });
    } catch {
      // 取消时没有请求；若请求已经开始，仍等待它结束。
    }
    await update;
  } finally {
    updatingStatusIds.value.delete(id);
  }
}

async function submitStatusChange(
  row: FoundationDatabaseTypeApi.DatabaseType,
  newStatus: number,
  text: string,
  scope: number,
) {
  const generation = queryGeneration;
  try {
    const updated = await updateDatabaseTypeStatus({
      id: row.id,
      status: newStatus,
    });
    if (scope !== scopeRevision) return;
    if (!updated) {
      message.error('更新连接器状态失败');
      return;
    }
    dataRevision++;
    row.status = newStatus;
    message.success(`${text}成功`);
    if (
      generation !== queryGeneration ||
      (appliedStatus !== undefined && appliedStatus !== newStatus)
    ) {
      handleRefresh();
    }
  } catch {
    // 请求客户端已提示错误，保持原状态。
  }
}

/** 删除连接器定义 */
async function handleDelete(row: FoundationDatabaseTypeApi.DatabaseType) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteDatabaseType(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 批量删除连接器定义 */
async function handleDeleteBatch() {
  await confirm($t('ui.actionMessage.deleteBatchConfirm'));
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deletingBatch'),
    duration: 0,
  });
  try {
    await deleteDatabaseTypeList(checkedIds.value);
    checkedIds.value = [];
    message.success($t('ui.actionMessage.deleteSuccess'));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const checkedIds = ref<FoundationDatabaseTypeApi.DatabaseType['id'][]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: FoundationDatabaseTypeApi.DatabaseType[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

/** 导出表格 */
// async function handleExport() {
//   const data = await exportDatabaseType(await gridApi.formApi.getValues());
//   downloadFileFromBlobPart({ fileName: '数据库类型.xls', source: data });
// }

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: ({ page }, formValues) => {
          const generation = ++queryGeneration;
          const scope = scopeRevision;
          const params = {
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          };
          const load = async (): ReturnType<typeof getDatabaseTypePage> => {
            while (true) {
              const revision = dataRevision;
              const result = await getDatabaseTypePage(params);
              if (scope !== scopeRevision) throw new Error('查询上下文已失效');
              if (generation !== queryGeneration) return await latestQuery!;
              if (revision !== dataRevision) continue;
              appliedStatus = formValues.status ?? undefined;
              return result;
            }
          };
          latestQuery = load();
          return latestQuery;
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<FoundationDatabaseTypeApi.DatabaseType>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});

watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    scopeRevision++;
    queryGeneration++;
    appliedStatus = undefined;
    updatingStatusIds.value.clear();
    void gridApi.grid?.loadData([]);
    if (access.accessToken) void gridApi.query();
  },
  { flush: 'sync' },
);
onUnmounted(() => { scopeRevision++; });
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="连接器列表">
      <template #status="{ row }">
        <Switch
          v-if="row.status === CommonStatusEnum.ENABLE || row.status === CommonStatusEnum.DISABLE"
          :checked="row.status"
          :checked-value="CommonStatusEnum.ENABLE"
          :un-checked-value="CommonStatusEnum.DISABLE"
          :checked-children="$t('common.enabled')"
          :un-checked-children="$t('common.disabled')"
          :disabled="!hasAccessByCodes(['dataforge:database-type:update'])"
          :loading="updatingStatusIds.has(`${scopeRevision}:${String(row.id)}`)"
          @update:checked="(value) => handleStatusChange(value as number, row)"
        />
        <span v-else>-</span>
      </template>
      <template #connectionIcon="{ row }">
        <ConnectionIcon
          :icon-path="row.iconPath"
          :type-code="row.typeCode"
          :alt="row.typeName"
          class="mx-auto size-10"
        />
      </template>
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:database-type:create'],
              onClick: handleCreate,
            },
            // {
            //   label: $t('ui.actionTitle.export'),
            //   type: 'primary',
            //   icon: ACTION_ICON.DOWNLOAD,
            //   auth: ['foundation:database-type:export'],
            //   onClick: handleExport,
            // },
            {
              label: $t('ui.actionTitle.deleteBatch'),
              type: 'primary',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:database-type:delete'],
              disabled: isEmpty(checkedIds),
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['dataforge:database-type:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:database-type:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:database-type:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.id]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
