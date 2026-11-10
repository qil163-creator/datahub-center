<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationSystemSourceApi } from '#/api/dataforge/systemsource';
import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';

import { ref } from 'vue';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { isEmpty } from '@vben/utils';

import { Card, message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteSystemSource,
  deleteSystemSourceList,
  getSystemSourcePage,
  updateSystemSource,
} from '#/api/dataforge/systemsource';
import { getSimpleDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { $t } from '#/locales';
import { DeptTreeSelect } from '#/views/system/dept/components';

import { useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const deptList = ref<SystemDeptApi.Dept[]>([]);
const userList = ref<SystemUserApi.User[]>([]);
const lookupLoaded = ref(false);
const sidebarCollapsed = ref(false);
let lookupDataPromise: Promise<void> | undefined;

/** 确保部门、用户字典已加载（避免表格首次查询时名称无法回显） */
async function ensureLookupData() {
  if (lookupLoaded.value) {
    return;
  }
  lookupDataPromise ??= Promise.all([
    getSimpleDeptList(),
    getSimpleUserList(),
  ]).then(([depts, users]) => {
    deptList.value = depts;
    userList.value = users;
    lookupLoaded.value = true;
  });
  await lookupDataPromise;
}

void ensureLookupData();

function getDeptName(deptId?: number) {
  if (deptId === null || deptId === undefined) {
    return '-';
  }
  return deptList.value.find((item) => item.id === Number(deptId))?.name || '-';
}

function getOwnerInfo(ownerUserId?: number) {
  if (!ownerUserId) {
    return { ownerUserName: '-', ownerMobile: '-' };
  }
  const user = userList.value.find((item) => item.id === Number(ownerUserId));
  return {
    ownerUserName: user?.nickname || '-',
    ownerMobile: user?.mobile || '-',
  };
}

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 选择部门 */
const searchDeptId = ref<number | undefined>(undefined);
async function handleDeptSelect(dept?: SystemDeptApi.Dept) {
  searchDeptId.value = dept?.id;
  handleRefresh();
}

/** 创建来源系统 */
function handleCreate() {
  formModalApi
    .setData(searchDeptId.value ? { deptId: searchDeptId.value } : {})
    .open();
}

/** 编辑来源系统 */
function handleEdit(row: FoundationSystemSourceApi.SystemSource) {
  formModalApi.setData(row).open();
}

/** 查看来源系统详情 */
function handleDetail(row: FoundationSystemSourceApi.SystemSource) {
  detailModalApi.setData(row).open();
}

/** 删除来源系统 */
async function handleDelete(row: FoundationSystemSourceApi.SystemSource) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteSystemSource(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 批量删除来源系统 */
async function handleDeleteBatch() {
  await confirm($t('ui.actionMessage.deleteBatchConfirm'));
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deletingBatch'),
    duration: 0,
  });
  try {
    await deleteSystemSourceList(checkedIds.value);
    checkedIds.value = [];
    message.success($t('ui.actionMessage.deleteSuccess'));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const checkedIds = ref<number[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: FoundationSystemSourceApi.SystemSource[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

/** 更新来源系统状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationSystemSourceApi.SystemSource,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${text}"${row.name}"吗?`);
  } catch {
    return false;
  }
  await updateSystemSource({
    id: row.id,
    name: row.name,
    icon: row.icon,
    url: row.url,
    type: row.type,
    ownerUserId: row.ownerUserId,
    description: row.description,
    sortOrder: row.sortOrder ?? 0,
    deptId: row.deptId,
    status: newStatus,
    remark: row.remark,
  });
  message.success(`${text}成功`);
  return true;
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
          await ensureLookupData();
          const result = await getSystemSourcePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            deptId: searchDeptId.value,
          });
          return {
            ...result,
            list: result.list.map((item) => {
              const { ownerUserName, ownerMobile } = getOwnerInfo(
                item.ownerUserId,
              );
              return {
                ...item,
                deptName: getDeptName(item.deptId),
                ownerUserName,
                ownerMobile,
              };
            }),
          };
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
  } as VxeTableGridOptions<FoundationSystemSourceApi.SystemSource>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />

    <div class="source-layout flex h-full min-h-0 w-full overflow-hidden">
      <Card
        v-show="!sidebarCollapsed"
        class="source-sidebar h-full w-1/6 shrink-0"
        :body-style="{
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          overflow: 'hidden',
          padding: '12px',
        }"
      >
        <DeptTreeSelect
          class="h-full min-h-0"
          horizontal-scroll
          show-expand-toggle
          @select="handleDeptSelect"
        />
      </Card>
      <SidebarCollapse
        :collapsed="sidebarCollapsed"
        label="部门树"
        @toggle="sidebarCollapsed = !sidebarCollapsed"
      />
      <div class="min-h-0 min-w-0 flex-1 overflow-hidden">
        <Grid table-title="来源系统列表">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: $t('common.create'),
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['dataforge:system-source:create'],
                  onClick: handleCreate,
                },
                {
                  label: $t('ui.actionTitle.deleteBatch'),
                  type: 'primary',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['dataforge:system-source:delete'],
                  disabled: isEmpty(checkedIds),
                  onClick: handleDeleteBatch,
                },
              ]"
            />
          </template>
          <template #description="{ row }">
            <TextEllipsisCell
              class="block whitespace-pre-wrap break-words"
              :content="row.description || '-'"
              :line="2"
              center
            />
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: $t('common.edit'),
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  auth: ['dataforge:system-source:update'],
                  onClick: handleEdit.bind(null, row),
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['dataforge:system-source:delete'],
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [row.id]),
                    confirm: handleDelete.bind(null, row),
                  },
                },
                {
                  label: $t('common.detail'),
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  auth: ['dataforge:system-source:query'],
                  onClick: handleDetail.bind(null, row),
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>
  </Page>
</template>

<style scoped>
@media (max-width: 767px) {
  .source-layout {
    flex-direction: column;
    overflow: auto;
  }

  .source-sidebar {
    width: 100%;
    height: 220px;
  }
}

@media (max-width: 767px) {
  .source-layout > :deep(.sidebar-collapse) {
    flex: 0 0 12px;
    width: 100%;
    min-width: 0;
    height: 12px;
  }

  .source-layout > :deep(.sidebar-collapse svg) {
    transform: rotate(90deg);
  }
}

@media (min-width: 641px) and (max-width: 767px) {
  .source-layout > :deep(.sidebar-collapse .sidebar-collapse__button),
  .source-layout
    > :deep(.sidebar-collapse.is-collapsed .sidebar-collapse__button) {
    top: -8px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }
}
</style>
