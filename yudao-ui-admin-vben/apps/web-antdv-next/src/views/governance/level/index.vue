<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyLevelApi } from '#/api/governance/level';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { deleteLevel, getLevelPage } from '#/api/governance/level';
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

/** 创建数据分级 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 编辑数据分级 */
function handleEdit(row: SafetyLevelApi.Level) {
  formModalApi.setData(row).open();
}

function handleDetail(row: SafetyLevelApi.Level) {
  detailModalApi.setData(row).open();
}

/** 删除数据分级 */
async function handleDelete(row: SafetyLevelApi.Level) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteLevel(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

// 批量删除数据分级（工具栏按钮启用时恢复）
// async function handleDeleteBatch() {
//   await confirm($t('ui.actionMessage.deleteBatchConfirm'));
//   const hideLoading = message.loading({
//     content: $t('ui.actionMessage.deletingBatch'),
//     duration: 0,
//   });
//   try {
//     await deleteLevelList(checkedIds.value);
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
  records: SafetyLevelApi.Level[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

// 导出表格（工具栏按钮启用时恢复）
// async function handleExport() {
//   const data = await exportLevel(await gridApi.formApi.getValues());
//   downloadFileFromBlobPart({ fileName: '数据分级.xls', source: data });
// }

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getLevelPage({
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
  } as VxeTableGridOptions<SafetyLevelApi.Level>,
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
    <Grid table-title="数据分级列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:level:create'],
              onClick: handleCreate,
            },
            // {
            //   label: $t('ui.actionTitle.export'),
            //   type: 'primary',
            //   icon: ACTION_ICON.DOWNLOAD,
            //   auth: ['safety:level:export'],
            //   onClick: handleExport,
            // },
            // {
            //   label: $t('ui.actionTitle.deleteBatch'),
            //   type: 'primary',
            //   danger: true,
            //   icon: ACTION_ICON.DELETE,
            //   auth: ['safety:level:delete'],
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
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:level:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:level:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:level:delete'],
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
