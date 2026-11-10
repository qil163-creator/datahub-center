<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatasourceDriverApi } from '#/api/dataforge/driver';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
// import { downloadFileFromBlobPart } from '@vben/utils';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteDatasourceDriver,
  // deleteDatasourceDriverList,
  // exportDatasourceDriver,
  getDatasourceDriverPage,
} from '#/api/dataforge/driver';
import { ConnectionIcon } from '#/components/connection-icon';
import { $t } from '#/locales';

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

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 创建数据源驱动管理 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 编辑数据源驱动管理 */
function handleEdit(row: FoundationDatasourceDriverApi.DatasourceDriver) {
  formModalApi.setData(row).open();
}

/** 查看数据源驱动详情 */
function handleDetail(row: FoundationDatasourceDriverApi.DatasourceDriver) {
  detailModalApi.setData(row).open();
}

/** 删除数据源驱动管理 */
async function handleDelete(
  row: FoundationDatasourceDriverApi.DatasourceDriver,
) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteDatasourceDriver(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 批量删除数据源驱动管理 */
// async function handleDeleteBatch() {
//   await confirm($t('ui.actionMessage.deleteBatchConfirm'));
//   const hideLoading = message.loading({
//     content: $t('ui.actionMessage.deletingBatch'),
//     duration: 0,
//   });
//   try {
//     await deleteDatasourceDriverList(checkedIds.value);
//     checkedIds.value = [];
//     message.success($t('ui.actionMessage.deleteSuccess'));
//     handleRefresh();
//   } finally {
//     hideLoading();
//   }
// }

const checkedIds = ref<number[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: FoundationDatasourceDriverApi.DatasourceDriver[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

/** 导出表格 */
// async function handleExport() {
//   const data = await exportDatasourceDriver(await gridApi.formApi.getValues());
//   downloadFileFromBlobPart({ fileName: '数据源驱动管理.xls', source: data });
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
        query: async ({ page }, formValues) => {
          return await getDatasourceDriverPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
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
  } as VxeTableGridOptions<FoundationDatasourceDriverApi.DatasourceDriver>,
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
    <Grid table-title="数据源驱动管理">
      <template #connectionIcon="{ row }">
        <ConnectionIcon :icon-path="row.icon" class="mx-auto size-10" />
      </template>
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:datasource-driver:create'],
              onClick: handleCreate,
            },
            // {
            //   label: $t('ui.actionTitle.export'),
            //   type: 'primary',
            //   icon: ACTION_ICON.DOWNLOAD,
            //   auth: ['foundation:datasource-driver:export'],
            //   onClick: handleExport,
            // },
            // {
            //   label: $t('ui.actionTitle.deleteBatch'),
            //   type: 'primary',
            //   danger: true,
            //   icon: ACTION_ICON.DELETE,
            //   auth: ['foundation:datasource-driver:delete'],
            //   disabled: isEmpty(checkedIds),
            //   onClick: handleDeleteBatch,
            // },
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
              auth: ['dataforge:datasource-driver:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:datasource-driver:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:datasource-driver:delete'],
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
