<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { Page, useVbenModal } from '@vben/common-ui';

import { Tag } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getAccessAlertPage } from '#/api/governance/security';

import {
  ACCESS_ALERT_HANDLE,
  ruleUnit,
  statusView,
  useGridColumns,
  useGridFormSchema,
} from './data';
import HandleForm from './modules/handle-form.vue';

defineOptions({ name: 'SafetyAccessAlert' });

const [HandleModal, handleModalApi] = useVbenModal({
  connectedComponent: HandleForm,
  destroyOnClose: true,
});

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 处理告警 */
function handleAlert(row: SafetySecurityApi.AccessAlert) {
  handleModalApi.setData(row).open();
}

const [Grid, gridApi] = useVbenVxeGrid<SafetySecurityApi.AccessAlert>({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getAccessAlertPage({
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
  } as VxeTableGridOptions<SafetySecurityApi.AccessAlert>,
});
</script>

<template>
  <Page auto-content-height>
    <HandleModal @success="handleRefresh" />
    <Grid
      table-title="异常访问告警列表"
      table-title-help="按用户、按统计窗口汇总敏感数据访问审计，超过阈值即告警；告警只含统计值，详细访问记录见“访问审计”"
    >
      <template #metric="{ row }">
        <span
          :class="{
            'text-destructive font-medium': row.metricValue > row.threshold,
          }"
        >
          {{ row.metricValue }}
        </span>
        / {{ row.threshold }} {{ ruleUnit(row.ruleCode) }}
      </template>
      <template #status="{ row }">
        <Tag :color="statusView(row.status).color">
          {{ statusView(row.status).label }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '处理',
              type: 'link',
              icon: 'lucide:shield-check',
              auth: [ACCESS_ALERT_HANDLE],
              ifShow: row.status === 'OPEN',
              onClick: handleAlert.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
