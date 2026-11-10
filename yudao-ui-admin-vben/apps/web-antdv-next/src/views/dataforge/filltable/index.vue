<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { Page, useVbenModal } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  cancelFillTable,
  deleteFillTable,
  downloadFillDataTemplate,
  getFillTablePage,
  releaseFillTable,
} from '#/api/dataforge/filltable';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { $t } from '#/locales';

import {
  FILL_TABLE_STATUS_OPTIONS,
  fillTableOptionLabel,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Columns from './modules/columns.vue';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'DataforgeFillTable' });

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [ColumnsModal, columnsModalApi] = useVbenModal({
  connectedComponent: Columns,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleDetail(row: DataforgeFillTableApi.FillTable) {
  detailModalApi.setData({ id: row.id }).open();
}

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(undefined).open();
}

function handleEdit(row: DataforgeFillTableApi.FillTable) {
  formModalApi.setData(row).open();
}

/** 字段配置 */
function handleColumns(row: DataforgeFillTableApi.FillTable) {
  columnsModalApi.setData(row).open();
}

async function handleRelease(row: DataforgeFillTableApi.FillTable) {
  const hideLoading = message.loading({ content: '正在发布...', duration: 0 });
  try {
    await releaseFillTable(row.id!);
    message.success('发布成功');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 取消发布：仅停用模板，物理表与已合并数据保留 */
async function handleCancel(row: DataforgeFillTableApi.FillTable) {
  const hideLoading = message.loading({
    content: '正在取消发布...',
    duration: 0,
  });
  try {
    await cancelFillTable(row.id!);
    message.success('已取消发布（物理表与已合并数据保留）');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleDownloadTemplate(row: DataforgeFillTableApi.FillTable) {
  if (row.id === undefined || row.id === null) {
    return;
  }
  const blob = await downloadFillDataTemplate(row.id);
  downloadFileFromBlobPart({
    fileName: `${row.tableEngName || row.tableCnName || 'fill-table'}-模板.xlsx`,
    source: blob,
  });
}

async function handleDelete(row: DataforgeFillTableApi.FillTable) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.tableEngName]),
    duration: 0,
  });
  try {
    await deleteFillTable(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.tableEngName]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

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
          return await getFillTablePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeFillTableApi.FillTable>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <ColumnsModal />
    <Grid table-title="填报表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:fill-table:create'],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 1 ? 'success' : 'default'">
          {{ fillTableOptionLabel(FILL_TABLE_STATUS_OPTIONS, row.status) }}
        </Tag>
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
              auth: ['dataforge:fill-table:update'],
              ifShow: row.status !== 1,
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['dataforge:fill-table:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: '下载模板',
              type: 'link',
              icon: ACTION_ICON.DOWNLOAD,
              auth: ['dataforge:fill-table:query'],
              onClick: handleDownloadTemplate.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '字段配置',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:fill-table:query'],
              onClick: handleColumns.bind(null, row),
            },
            {
              label: row.status === 1 ? '取消发布' : '发布',
              type: 'link',
              danger: row.status === 1,
              icon: row.status === 1 ? 'lucide:square' : 'lucide:send',
              auth: ['dataforge:fill-table:release'],
              popConfirm: {
                title:
                  row.status === 1
                    ? `确认取消发布「${row.tableEngName}」吗？仅停用模板，物理表与已合并数据保留。`
                    : `确认发布「${row.tableEngName}」吗？目标表不存在时将创建，已存在时将校验表结构。`,
                confirm:
                  row.status === 1
                    ? handleCancel.bind(null, row)
                    : handleRelease.bind(null, row),
              },
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:fill-table:delete'],
              ifShow: row.status !== 1,
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.tableEngName]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
:deep(.filltable-action-column),
:deep(.filltable-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
