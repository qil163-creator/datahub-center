<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { MetaSearchHit } from '#/api/governance/metaobject';

import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { searchMetaObject } from '#/api/governance/metaobject';
import { parseResourceId } from '#/utils/resource-id';

import {
  CHANGE_OBJECT_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  PUBLISH_STATUS_OPTIONS,
} from '../metaobject/data';

defineOptions({ name: 'GovernanceMetaSearch' });

const router = useRouter();
const { hasAccessByCodes } = useAccess();

function handleDetail(row: MetaSearchHit) {
  const id = parseResourceId(row.tableId);
  if (!id || !hasAccessByCodes(['governance:meta-table:query'])) return;
  void router.push({ name: 'GovernanceMetaSearchDetail', params: { id } });
}

const [Grid] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'keyword',
        label: '关键字',
        component: 'Input',
        componentProps: {
          allowClear: true,
          placeholder: '表名 / 字段名 / 业务名称 / 注释',
        },
      },
      {
        fieldName: 'layer',
        label: '分层',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: ['ODS', 'DWD', 'DWS', 'ADS', 'DIM'].map((value) => ({
            label: value,
            value,
          })),
        },
      },
      {
        fieldName: 'publishStatus',
        label: '发布状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: PUBLISH_STATUS_OPTIONS,
        },
      },
      {
        fieldName: 'ownerMissing',
        label: '认责',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [{ label: '仅看未认责', value: true }],
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      {
        field: 'objectType',
        title: '对象',
        width: 70,
        slots: { default: 'objectType' },
      },
      { field: 'dbName', title: '库', width: 110 },
      { field: 'tableName', title: '表名', minWidth: 160 },
      { field: 'columnName', title: '字段名', minWidth: 140 },
      { field: 'businessName', title: '业务名称', minWidth: 140 },
      { field: 'comment', title: '注释', minWidth: 180 },
      { field: 'hitField', title: '命中', width: 90 },
      {
        field: 'publishStatus',
        title: '发布',
        width: 80,
        slots: { default: 'publishStatus' },
      },
      { field: 'structVersion', title: '版本', width: 90 },
      {
        title: '操作',
        width: 100,
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
          return await searchMetaObject({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'objectId', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<MetaSearchHit>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="元数据检索">
      <template #objectType="{ row }">
        {{ optionLabel(CHANGE_OBJECT_TYPE_OPTIONS, row.objectType) }}
      </template>
      <template #publishStatus="{ row }">
        <Tag :color="optionColor(PUBLISH_STATUS_OPTIONS, row.publishStatus)">
          {{ optionLabel(PUBLISH_STATUS_OPTIONS, row.publishStatus) }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:meta-table:query'],
              onClick: handleDetail.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
