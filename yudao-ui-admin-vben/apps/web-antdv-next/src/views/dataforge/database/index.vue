<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime, isEmpty } from '@vben/utils';

import { message, Tag, Tooltip } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDatabase,
  deleteDatabaseList,
  diagnoseDatabase,
  getDatabasePage,
  updateDatabase,
} from '#/api/dataforge/database';
import { $t } from '#/locales';

import { hasDatabaseMetadata } from './connection-capabilities';
import {
  connectionStatusColor,
  connectionStatusLabel,
  DATABASE_CONNECTION_STATUS,
} from './connection-status';
import { useGridColumns, useGridFormSchema } from './data';
import { diagnosisSummary } from './diagnosis';
import CreateWizard from './modules/create-wizard.vue';
import DiagnosisModal from './modules/diagnosis-modal.vue';
import Form from './modules/form.vue';
import Preview from './modules/preview.vue';
import TypeIconCell from './modules/type-icon-cell.vue';
import TypeSelect from './modules/type-select.vue';

const [TypeSelectModal, typeSelectModalApi] = useVbenModal({
  connectedComponent: TypeSelect,
  destroyOnClose: true,
});

const [CreateWizardModal, createWizardModalApi] = useVbenModal({
  connectedComponent: CreateWizard,
  destroyOnClose: true,
});

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: Preview,
  destroyOnClose: true,
});
const [ConnectionDiagnosisModal, diagnosisModalApi] = useVbenModal({
  connectedComponent: DiagnosisModal,
  destroyOnClose: true,
});

const formDraft = ref<Partial<FoundationDatabaseApi.Database>>();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const editScope = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
let queryInFlight = false;
let pendingCreateRefresh = false;
function clearEditDraft() {
  formDraft.value = undefined;
}
function clearPageContext() {
  clearEditDraft();
  pendingCreateRefresh = false;
}
watch(editScope, clearPageContext, { flush: 'sync' });
onDeactivated(clearPageContext);
onBeforeUnmount(clearPageContext);

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

function handleCreateSuccess() {
  if (queryInFlight) {
    pendingCreateRefresh = true;
    return;
  }
  void gridApi.reload();
}

function handleQuerySettled() {
  queryInFlight = false;
  if (pendingCreateRefresh) {
    pendingCreateRefresh = false;
    void gridApi.reload();
  }
}

/** 创建数据源中心--数据库信息 */
function handleCreate() {
  formDraft.value = undefined;
  createWizardModalApi.open();
}

/** 选择数据库类型后进入表单 */
function handleTypeSelected(type: FoundationDatabaseTypeApi.DatabaseType) {
  const draft = formDraft.value;
  formDraft.value = undefined;
  if (draft?.id) formModalApi.setData({ selectedType: type, draft }).open();
}

/** 返回重新选择数据库类型 */
function handleChangeType(draft: Partial<FoundationDatabaseApi.Database>) {
  formDraft.value = draft;
  typeSelectModalApi.open();
}

/** 查看数据源中心--数据库信息详情 */
function handleDetail(row: FoundationDatabaseApi.Database) {
  router.push({
    name: 'DataforgeDatabaseDetail',
    params: { id: String(row.id) },
  });
}

/** 预览数据源下的表与样例数据 */
function handlePreview(row: FoundationDatabaseApi.Database) {
  if (!hasDatabaseMetadata(row)) return;
  previewModalApi.setData(row).open();
}

/** 测试数据库连接 */
function handleTest(row: FoundationDatabaseApi.Database) {
  const databaseId = row.id;
  diagnosisModalApi
    .setData({
      summary: diagnosisSummary(row),
      execute: (includeWriteChecks: boolean, signal: AbortSignal) =>
        diagnoseDatabase({ databaseId, includeWriteChecks }, signal),
    })
    .open();
}

/** 编辑数据源中心--数据库信息 */
function handleEdit(row: FoundationDatabaseApi.Database) {
  formDraft.value = undefined;
  formModalApi.setData({ id: row.id }).open();
}

/** 更新数据库信息状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationDatabaseApi.Database,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${text}"${row.name || row.id}"吗?`);
  } catch {
    return false;
  }
  await updateDatabase({
    ...row,
    status: newStatus,
  });
  message.success(`${text}成功`);
  return true;
}

/** 删除数据源中心--数据库信息 */
async function handleDelete(row: FoundationDatabaseApi.Database) {
  const name = row.name || String(row.id);
  try {
    await confirm($t('ui.actionMessage.deleteConfirm', [name]));
  } catch {
    return;
  }
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [name]),
    duration: 0,
  });
  try {
    await deleteDatabase(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [name]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 批量删除数据源中心--数据库信息 */
async function handleDeleteBatch() {
  await confirm($t('ui.actionMessage.deleteBatchConfirm'));
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deletingBatch'),
    duration: 0,
  });
  try {
    await deleteDatabaseList(checkedIds.value);
    checkedIds.value = [];
    message.success($t('ui.actionMessage.deleteSuccess'));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const checkedIds = ref<FoundationDatabaseApi.Id[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: FoundationDatabaseApi.Database[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useGridColumns(handleStatusChange),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          queryInFlight = true;
          return await getDatabasePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
        querySuccess: handleQuerySettled,
        queryError: handleQuerySettled,
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
  } as VxeTableGridOptions<FoundationDatabaseApi.Database>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});
</script>

<template>
  <Page auto-content-height>
    <TypeSelectModal @select="handleTypeSelected" @closed="clearEditDraft" />
    <CreateWizardModal @success="handleCreateSuccess" />
    <FormModal @change-type="handleChangeType" @success="handleRefresh" />
    <PreviewModal />
    <ConnectionDiagnosisModal @settled="handleRefresh" />
    <Grid table-title="连接信息列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:database:create'],
              onClick: handleCreate,
            },
            {
              label: $t('ui.actionTitle.deleteBatch'),
              type: 'primary',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:database:delete'],
              disabled: isEmpty(checkedIds),
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #databaseType="{ row }">
        <TypeIconCell
          :icon-path="row.iconPath"
          :type-code="row.typeCode || row.productId"
          :type-name="row.databaseTypeName"
        />
      </template>
      <template #connectionStatus="{ row }">
        <Tooltip
          :title="
            row.connectionStatus === DATABASE_CONNECTION_STATUS.OFFLINE &&
            row.lastTestMsg
              ? row.lastTestMsg
              : undefined
          "
        >
          <div class="flex flex-col items-start leading-tight">
            <Tag :color="connectionStatusColor(row.connectionStatus)">
              {{ connectionStatusLabel(row.connectionStatus) }}
            </Tag>
            <span
              v-if="row.lastTestTime"
              class="text-muted-foreground mt-0.5 text-xs"
            >
              {{ formatDateTime(row.lastTestTime) }}
            </span>
          </div>
        </Tooltip>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['dataforge:database:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: '连接测试',
              type: 'link',
              icon: ACTION_ICON.REFRESH,
              auth: ['dataforge:database:query'],
              onClick: handleTest.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '预览数据',
              type: 'link',
              icon: ACTION_ICON.PREVIEW,
              auth: ['dataforge:database:preview'],
              ifShow: hasDatabaseMetadata(row),
              onClick: handlePreview.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:database:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:database:delete'],
              onClick: handleDelete.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
:deep(.database-action-column),
:deep(.database-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
