<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityCheckObjectApi } from '#/api/governance/quality/checkobject';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCheckObject,
  getCheckObjectPage,
} from '#/api/governance/quality/checkobject';
import { $t } from '#/locales';

import CheckObjectForm from './modules/form.vue';

defineOptions({ name: 'GovernanceQualityCheckObject' });

const formRef = ref<InstanceType<typeof CheckObjectForm>>();

function handleRefresh() {
  gridApi.query();
}

async function handleDelete(row: QualityCheckObjectApi.CheckObject) {
  await deleteCheckObject(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'objectName',
        label: '检查对象',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入检查对象名称' },
      },
      {
        fieldName: 'tableName',
        label: '表名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入表名称' },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      {
        field: 'objectName',
        title: '检查对象名称',
        minWidth: 180,
        align: 'left',
      },
      { field: 'datasourceConnId', title: '数据源连接', width: 110 },
      {
        field: 'datasourceType',
        title: '数据连接类型',
        width: 140,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      { field: 'tableName', title: '表名称', minWidth: 160, align: 'left' },
      {
        field: 'formatName',
        title: '格式名称',
        width: 100,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'remark',
        title: '备注',
        minWidth: 140,
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 180,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getCheckObjectPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityCheckObjectApi.CheckObject>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="检查对象">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-check-object:create'],
              onClick: () => formRef?.open(),
            },
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
              auth: ['governance:quality-check-object:update'],
              onClick: () => formRef?.open(row.id),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-check-object:delete'],
              popConfirm: {
                title: `确认删除检查对象「${row.objectName}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <CheckObjectForm ref="formRef" @saved="handleRefresh" />
  </Page>
</template>
