<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { Page, useVbenModal } from '@vben/common-ui';

import { Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getAccessLogPage } from '#/api/governance/security';
import { $t } from '#/locales';

import {
  ACCESS_TYPE_OPTIONS,
  MASKED_FLAG_OPTIONS,
  optionColor,
  optionLabel,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Detail from './modules/detail.vue';

defineOptions({ name: 'SafetyAccessLog' });

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleDetail(row: SafetySecurityApi.AccessLog) {
  detailModalApi.setData(row).open();
}

const [Grid] = useVbenVxeGrid({
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
          return await getAccessLogPage({
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
  } as VxeTableGridOptions<SafetySecurityApi.AccessLog>,
});
</script>

<template>
  <Page auto-content-height>
    <DetailModal />
    <Grid table-title="敏感数据访问审计">
      <template #accessType="{ row }">
        <Tag :color="optionColor(ACCESS_TYPE_OPTIONS, row.accessType)">
          {{ optionLabel(ACCESS_TYPE_OPTIONS, row.accessType) }}
        </Tag>
      </template>
      <template #maskedFlag="{ row }">
        <Tag :color="optionColor(MASKED_FLAG_OPTIONS, row.maskedFlag)">
          {{ optionLabel(MASKED_FLAG_OPTIONS, row.maskedFlag) }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:safety-access-log:query'],
              onClick: handleDetail.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
