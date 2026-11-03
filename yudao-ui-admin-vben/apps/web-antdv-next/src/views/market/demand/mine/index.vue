<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { MarketApi } from '#/api/asset/market';

import { Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  closeMarketDemand,
  deleteMarketDemand,
  getMarketDemandPage,
  publishMarketDemand,
} from '#/api/asset/market';

import {
  DEMAND_STATUS_OPTIONS,
  DEMAND_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  URGENCY_OPTIONS,
  useDemandColumns,
  useDemandFormSchema,
} from '../../data';
import DemandDetail from '../../modules/demand-detail.vue';
import DemandForm from '../../modules/demand-form.vue';

defineOptions({ name: 'MarketDemandMine' });

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: DemandForm,
  destroyOnClose: true,
});
const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: DemandDetail,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: { schema: useDemandFormSchema() },
  gridOptions: {
    columns: useDemandColumns(),
    height: 'auto',
    showOverflow: false,
    showHeaderOverflow: true,
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getMarketDemandPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ifSelf: 1,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<MarketApi.Demand>,
});

async function handlePublish(row: MarketApi.Demand) {
  await publishMarketDemand(row.id!);
  message.success('已发布到需求大厅');
  handleRefresh();
}

async function handleClose(row: MarketApi.Demand, status: number) {
  await closeMarketDemand({ id: row.id!, status });
  message.success(status === 5 ? '已撤销' : '已关闭');
  handleRefresh();
}

async function handleDelete(row: MarketApi.Demand) {
  await deleteMarketDemand(row.id!);
  message.success('已删除');
  handleRefresh();
}
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailDrawer @success="handleRefresh" />
    <Grid :style="{ '--vxe-ui-font-size-small': '14px' }" table-title="我的需求">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '提出需求',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['asset:demand:create'],
              onClick: () => formModalApi.setData({}).open(),
            },
          ]"
        />
      </template>
      <template #demandType="{ row }">
        {{ optionLabel(DEMAND_TYPE_OPTIONS, row.demandType) }}
      </template>
      <template #urgency="{ row }">
        <Tag :color="optionColor(URGENCY_OPTIONS, row.urgency)">
          {{ optionLabel(URGENCY_OPTIONS, row.urgency) }}
        </Tag>
      </template>
      <template #status="{ row }">
        <Tag :color="optionColor(DEMAND_STATUS_OPTIONS, row.status)">
          {{ optionLabel(DEMAND_STATUS_OPTIONS, row.status) }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: () =>
                detailDrawerApi.setData({ id: row.id, mode: 'mine' }).open(),
            },
          ]"
          :drop-down-actions="[
            {
              label: '发布',
              type: 'link',
              icon: 'lucide:send',
              auth: ['asset:demand:submit'],
              ifShow: row.status === 0,
              popConfirm: {
                title: '确认发布到需求大厅？',
                confirm: () => handlePublish(row),
              },
            },
            {
              label: '编辑',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['asset:demand:create'],
              ifShow: [0, 1, 2].includes(row.status ?? -1),
              onClick: () => formModalApi.setData({ id: row.id }).open(),
            },
            {
              label: '关闭',
              type: 'link',
              icon: ACTION_ICON.CLOSE,
              auth: ['asset:demand:close'],
              ifShow: [1, 2, 3].includes(row.status ?? -1),
              popConfirm: {
                title: '确认关闭该需求？',
                confirm: () => handleClose(row, 4),
              },
            },
            {
              label: '撤销',
              type: 'link',
              danger: true,
              icon: 'lucide:undo-2',
              ifShow: [0, 1].includes(row.status ?? -1),
              popConfirm: {
                title: '确认撤销该需求？',
                confirm: () => handleClose(row, 5),
              },
            },
            {
              label: '删除',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              ifShow: [0, 5].includes(row.status ?? -1),
              popConfirm: {
                title: `确认删除「${row.title}」吗？`,
                confirm: () => handleDelete(row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
:deep(.market-action-column),
:deep(.market-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
