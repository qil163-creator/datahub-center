<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { MarketApi } from '#/api/asset/market';

import { Page, useVbenDrawer } from '@vben/common-ui';

import { Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getMarketDemandPage } from '#/api/asset/market';

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

defineOptions({ name: 'MarketDemandHall' });

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: DemandDetail,
  destroyOnClose: true,
});

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
</script>

<template>
  <Page auto-content-height>
    <DetailDrawer @success="gridApi.query()" />
    <Grid :style="{ '--vxe-ui-font-size-small': '14px' }" table-title="需求大厅">
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
              label: '查看',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: () =>
                detailDrawerApi.setData({ id: row.id, mode: 'hall' }).open(),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
