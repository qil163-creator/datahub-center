<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaTagApi } from '#/api/governance/metatag';

import { Page, useVbenModal } from '@vben/common-ui';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { deleteMetaTag, getMetaTagPage } from '#/api/governance/metatag';

import { useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'GovernanceMetaTag' });

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(undefined).open();
}

function handleEdit(row: GovernanceMetaTagApi.Tag) {
  formModalApi.setData(row).open();
}

function handleDetail(row: GovernanceMetaTagApi.Tag) {
  detailModalApi.setData(row).open();
}

async function handleDelete(row: GovernanceMetaTagApi.Tag) {
  await deleteMetaTag(row.id!);
  message.success('删除成功');
  handleRefresh();
}

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
          return await getMetaTagPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<GovernanceMetaTagApi.Tag>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="元数据标签">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:meta-tag:create'],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #name="{ row }">
        <Tag :color="row.color || 'blue'">{{ row.name }}</Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '编辑',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:meta-tag:update'],
              onClick: handleEdit.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:meta-tag:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: '删除',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:meta-tag:delete'],
              popConfirm: {
                title: row.refCount
                  ? `标签仍被 ${row.refCount} 个对象引用，无法删除`
                  : `确认删除标签「${row.name}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
              disabled: (row.refCount ?? 0) > 0,
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
:deep(.metatag-action-column),
:deep(.metatag-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
