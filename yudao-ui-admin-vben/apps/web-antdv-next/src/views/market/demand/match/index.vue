<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { MarketApi } from '#/api/asset/market';

import { Page, useVbenDrawer } from '@vben/common-ui';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { closeMarketDemand, getMarketDemandPage } from '#/api/asset/market';

import DemandDetail from '../../modules/demand-detail.vue';
import {
  DEMAND_STATUS_OPTIONS,
  DEMAND_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  URGENCY_OPTIONS,
  useDemandColumns,
  useDemandFormSchema,
} from '../data';

defineOptions({ name: 'MarketDemandMatch' });

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
            ifSelf: 0,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<MarketApi.Demand>,
});

async function handleReject(row: MarketApi.Demand) {
  await closeMarketDemand({
    id: row.id!,
    status: 6,
    closeReason: '供给方无法满足',
  });
  message.success('已驳回需求');
  handleRefresh();
}
</script>

<template>
  <Page auto-content-height>
    <DetailDrawer @success="handleRefresh" />
    <Grid :style="{ '--vxe-ui-font-size-small': '14px' }" table-title="需求对接">
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
              label: '对接',
              type: 'link',
              icon: 'lucide:link',
              auth: ['asset:demand:match'],
              onClick: () =>
                detailDrawerApi.setData({ id: row.id, mode: 'match' }).open(),
            },
            {
              label: '驳回',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.CLOSE,
              auth: ['asset:demand:match'],
              ifShow: [1, 2].includes(row.status ?? -1),
              popConfirm: {
                title: '确认驳回该需求？需求方将看到无法供给。',
                confirm: () => handleReject(row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
